package com.codelens.CodeLens.embedding;

import com.codelens.CodeLens.model.CodeChunk;
import com.codelens.CodeLens.model.RepositoryStatus;
import com.codelens.CodeLens.repository.CodeChunkRepository;
import com.codelens.CodeLens.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Drives the embedding phase: loads all un-embedded CodeChunks for a
 * repository and calls GeminiEmbeddingClient for each one.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHAT IS @Async? (Beginner explanation)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Normally, when your code calls a method, it WAITS for that method to finish
 * before moving to the next line. This is called "synchronous" execution:
 *
 *   Thread: uploadZip() → extractAndSave() → [WAITS 30 seconds for embeddings] → return
 *   User:   [staring at a loading spinner for 30 seconds]
 *
 * With @Async, Spring hands the method off to a SEPARATE THREAD and returns
 * immediately. The original thread continues without waiting:
 *
 *   Thread 1 (HTTP):        uploadZip() → extractAndSave() → return 201 Created ← USER GETS THIS FAST
 *   Thread 2 (background):                                  → indexEmbeddingsAsync() [runs in background]
 *
 * The user gets their HTTP 201 response almost instantly. The embedding
 * job runs in a background thread and the user can poll GET /stats to
 * see progress (the embedding column fills in gradually).
 *
 * HOW DOES SPRING KNOW TO RUN IT ASYNC?
 *   Spring wraps your class in a proxy. When another bean calls
 *   indexEmbeddingsAsync(), the proxy intercepts the call, submits the
 *   real method to a thread pool, and returns immediately.
 *
 * IMPORTANT RULE: @Async only works when the method is called from a
 * DIFFERENT BEAN. Calling this.indexEmbeddingsAsync() from inside the
 * same class bypasses the proxy and runs synchronously. Always call it
 * from another class (like RepositoryController does here).
 *
 * HOW TO ENABLE @Async IN YOUR APP:
 *   Add @EnableAsync to your main class (CodeLensApplication.java).
 *   We do this in the next step.
 *   Without @EnableAsync, @Async annotations are silently ignored!
 *
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingIndexingService {

    private final CodeChunkRepository   codeChunkRepository;
    private final RepositoryRepository  repositoryRepository;
    private final GeminiEmbeddingClient geminiEmbeddingClient;

    /**
     * Background embedding job for a repository.
     *
     * Loops through every CodeChunk that doesn't have an embedding yet
     * (embedding IS NULL), calls the Gemini API for each, and saves the result.
     *
     * @Async
     *   — Tells Spring to run this method on a background thread from its
     *     managed thread pool. The caller (RepositoryController) returns its
     *     HTTP response immediately without waiting for this to finish.
     *
     * @Transactional
     *   — Each chunk's save runs inside a transaction. We DON'T wrap the
     *     entire method in one big transaction because:
     *     a) A single transaction held open for hundreds of API calls would
     *        lock DB rows for too long.
     *     b) If one chunk fails, we want the OTHERS to still be saved.
     *     Instead, each codeChunkRepository.save() auto-commits individually.
     *     (Spring's default transaction propagation handles this correctly.)
     *
     * @param repositoryId The repository whose chunks need embeddings
     */
    @Async
    public void indexEmbeddingsAsync(Long repositoryId) {
        log.info("[EMBED] Starting async embedding for repository id={}", repositoryId);

        // ── Step 1: Find all chunks that still need an embedding ─────────
        // findByRepositoryIdAndEmbeddingIsNull() is a Spring Data derived
        // query — it translates to: SELECT * FROM code_chunk
        //                           WHERE repository_id = ? AND embedding IS NULL
        List<CodeChunk> unembeddedChunks =
                codeChunkRepository.findByRepositoryIdAndEmbeddingIsNull(repositoryId);

        int total   = unembeddedChunks.size();
        int success = 0;
        int failed  = 0;

        log.info("[EMBED] {} chunks to embed for repository id={}", total, repositoryId);

        if (total == 0) {
            log.info("[EMBED] Nothing to do — all chunks already have embeddings.");
            markEmbeddingComplete(repositoryId);
            return;
        }

        // ── Step 2: Process each chunk one at a time ─────────────────────
        for (int i = 0; i < unembeddedChunks.size(); i++) {
            CodeChunk chunk = unembeddedChunks.get(i);

            // Log progress every 10 chunks so you can watch it work
            if ((i + 1) % 10 == 0 || i == 0) {
                log.info("[EMBED] Progress: {}/{} chunks embedded for repo id={}",
                        i + 1, total, repositoryId);
            }

            // ── Step 3: Call the Gemini API ───────────────────────────────
            // embed() returns Optional.empty() if the API call fails.
            // We skip failures rather than crashing — other chunks still get embedded.
            Optional<String> embeddingJson =
                    geminiEmbeddingClient.embed(chunk.getContent());

            if (embeddingJson.isPresent()) {
                // ── Step 4: Save the embedding ────────────────────────────
                chunk.setEmbedding(embeddingJson.get());
                codeChunkRepository.save(chunk);
                // save() issues an UPDATE ... SET embedding = ? WHERE id = ?
                // It knows to UPDATE (not INSERT) because the entity already has an id.
                success++;

            } else {
                // API call failed — log and skip. The chunk stays with embedding=NULL.
                // You can re-run this service later to retry failed chunks.
                log.warn("[EMBED] Failed to embed chunk id={} (file chunk lines {}–{}). Skipping.",
                        chunk.getId(), chunk.getStartLine(), chunk.getEndLine());
                failed++;
            }

            // ── Step 5: Rate limiting delay ───────────────────────────────
            // Gemini free tier: 15 requests per minute = 1 request every 4 seconds.
            // 200ms between calls lets us do ~5 req/sec — well within limits.
            //
            // Thread.sleep() pauses THIS background thread only.
            // The HTTP server threads keep handling other requests normally.
            //
            // InterruptedException is thrown if the thread is asked to stop
            // (e.g. application shutdown). We restore the interrupt flag and exit.
            if (i < unembeddedChunks.size() - 1) { // No need to sleep after the last one
                try {
                    Thread.sleep(200); // 200 milliseconds = 0.2 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore interrupt flag
                    log.warn("[EMBED] Embedding thread interrupted. Stopping early.");
                    break;
                }
            }
        }

        // ── Step 6: Final summary ─────────────────────────────────────────
        log.info("[EMBED] Completed for repository id={}. Success: {}, Failed/Skipped: {}",
                repositoryId, success, failed);

        // Mark repository as EMBEDDING_DONE if all succeeded
        // (optional — helpful for a future status polling endpoint)
        if (failed == 0) {
            markEmbeddingComplete(repositoryId);
        }
    }

    /**
     * Updates the repository status to READY after successful embedding.
     * READY here means: "all chunks are embedded and the repo is queryable."
     *
     * Uses a separate @Transactional method so the repository row is updated
     * in its own clean transaction.
     */
    @Transactional
    public void markEmbeddingComplete(Long repositoryId) {
        repositoryRepository.findById(repositoryId).ifPresent(repo -> {
            repo.setStatus(RepositoryStatus.READY);
            repositoryRepository.save(repo);
            log.info("[EMBED] Repository id={} is now fully indexed and ready.", repositoryId);
        });
    }
}
