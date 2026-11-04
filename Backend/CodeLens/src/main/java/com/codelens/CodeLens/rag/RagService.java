package com.codelens.CodeLens.rag;

import com.codelens.CodeLens.dto.ChatResponseDto;
import com.codelens.CodeLens.dto.SourceRef;
import com.codelens.CodeLens.embedding.GeminiEmbeddingClient;
import com.codelens.CodeLens.model.CodeChunk;
import com.codelens.CodeLens.model.CodeFile;
import com.codelens.CodeLens.repository.CodeFileRepository;
import com.codelens.CodeLens.exception.RepositoryNotFoundException;
import com.codelens.CodeLens.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates the full Retrieval-Augmented Generation (RAG) pipeline:
 *
 * 1. RETRIEVE:
 *    • Embed the incoming user question into a vector using GeminiEmbeddingClient.
 *    • Scan repository chunks and find the top 6 most relevant code snippets
 *      using SimilaritySearchService (cosine similarity).
 *    • Resolve file paths for the retrieved chunks using CodeFileRepository.
 *
 * 2. AUGMENT:
 *    • Format a structured prompt using PromptBuilder that injects the retrieved
 *      code snippets into strict system guardrails.
 *
 * 3. GENERATE:
 *    • Send the augmented prompt to GroqChatClient (Llama 3.3 70B) to generate
 *      an accurate, citation-backed response.
 *    • Return the final ChatResponseDto with answer text and source citations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RepositoryRepository repositoryRepository;
    private final GeminiEmbeddingClient geminiEmbeddingClient;
    private final SimilaritySearchService similaritySearchService;
    private final CodeFileRepository codeFileRepository;
    private final PromptBuilder promptBuilder;
    private final GroqChatClient groqChatClient;

    /**
     * Answers a natural language question about a specific repository.
     *
     * @param repositoryId The ID of the repository to query
     * @param question     The user's question
     * @return ChatResponseDto containing the AI response and cited source locations
     */
    public ChatResponseDto chat(Long repositoryId, String question) {
        if (question == null || question.isBlank()) {
            return new ChatResponseDto("Question cannot be empty.", Collections.emptyList());
        }

        if (repositoryId == null || !repositoryRepository.existsById(repositoryId)) {
            throw new RepositoryNotFoundException(repositoryId);
        }

        log.info("RAG chat initiated for repositoryId={}: \"{}\"", repositoryId, question);

        // ── Step 1: Embed the user's question ────────────────────────────────
        // We convert the question text into a 768-dimensional float vector.
        Optional<String> questionEmbeddingJsonOpt = geminiEmbeddingClient.embed(question);
        if (questionEmbeddingJsonOpt.isEmpty()) {
            log.error("Failed to generate embedding for question: {}", question);
            return new ChatResponseDto(
                    "Unable to embed question. Please check that 'app.gemini.api-key' is configured properly in application.properties.",
                    Collections.emptyList()
            );
        }

        float[] queryEmbedding;
        try {
            queryEmbedding = similaritySearchService.parseEmbedding(questionEmbeddingJsonOpt.get());
        } catch (Exception e) {
            log.error("Failed to parse query embedding JSON: {}", e.getMessage());
            return new ChatResponseDto(
                    "Failed to process query vector: " + e.getMessage(),
                    Collections.emptyList()
            );
        }

        // ── Step 2: Retrieve Top 6 Chunks via Cosine Similarity ──────────────
        List<ScoredChunk> topChunks = similaritySearchService.topKSimilar(queryEmbedding, repositoryId, 6);

        if (topChunks.isEmpty()) {
            log.warn("No indexed chunks found for repositoryId={}", repositoryId);
            return new ChatResponseDto(
                    "No indexed code chunks found for this repository. Please ensure the repository is uploaded and indexing has completed.",
                    Collections.emptyList()
            );
        }

        log.info("Retrieved {} relevant chunks for repositoryId={}", topChunks.size(), repositoryId);

        // ── Step 3: Resolve File Paths for Retrieved Chunks ───────────────────
        // Chunks store `fileId`. We batch-load the corresponding CodeFile entities
        // in a single SELECT query to get their relative file paths.
        List<Long> fileIds = topChunks.stream()
                .map(sc -> sc.chunk().getFileId())
                .distinct()
                .toList();

        Map<Long, String> fileIdToPath = codeFileRepository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(CodeFile::getId, CodeFile::getPath, (p1, p2) -> p1));

        // ── Step 4: Build Snippet Contexts and Source References ─────────────
        List<PromptBuilder.SnippetContext> snippetContexts = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();

        for (ScoredChunk scoredChunk : topChunks) {
            CodeChunk chunk = scoredChunk.chunk();
            String path = fileIdToPath.getOrDefault(chunk.getFileId(), "UnknownFile.java");

            snippetContexts.add(new PromptBuilder.SnippetContext(
                    path,
                    chunk.getStartLine(),
                    chunk.getEndLine(),
                    chunk.getContent()
            ));

            sources.add(new SourceRef(path, chunk.getStartLine(), chunk.getEndLine()));
        }

        // ── Step 5: Construct Augmented Prompt ────────────────────────────────
        String prompt = promptBuilder.buildPrompt(question, snippetContexts);
        log.debug("Constructed RAG prompt (length={} chars)", prompt.length());

        // ── Step 6: Generate Answer with Groq (Llama 3.3 70B) ─────────────────
        String answer = groqChatClient.chat(prompt);

        return new ChatResponseDto(answer, sources);
    }
}
