package com.codelens.CodeLens.controller;

import com.codelens.CodeLens.dto.RepositoryStatsDto;
import com.codelens.CodeLens.dto.UploadResponseDto;
import com.codelens.CodeLens.embedding.EmbeddingIndexingService;
import com.codelens.CodeLens.exception.RepositoryNotFoundException;
import com.codelens.CodeLens.ingestion.ZipExtractorService;
import com.codelens.CodeLens.model.Repository;
import com.codelens.CodeLens.model.RepositoryStatus;
import com.codelens.CodeLens.repository.RepositoryRepository;
import com.codelens.CodeLens.service.RepositoryStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * HTTP entry point for all repository-related operations.
 *
 * ─── What is a REST Controller? ───────────────────────────────────────────
 *
 * @RestController
 *   — Combines two annotations:
 *     @Controller   → tells Spring this class handles HTTP requests
 *     @ResponseBody → tells Spring to serialize return values as JSON
 *                     (using Jackson, which is included with spring-boot-starter-web)
 *
 *   Without @ResponseBody, Spring would try to find a view template (like a
 *   Thymeleaf HTML file). With @ResponseBody, the object you return from a
 *   method is automatically converted to JSON and written to the HTTP response.
 *
 * @RequestMapping("/api/repositories")
 *   — A BASE PATH prefix applied to ALL methods in this class.
 *     So a method mapped to "/upload" becomes "/api/repositories/upload".
 *     This keeps related endpoints grouped under one URL prefix.
 *
 * @Slf4j, @RequiredArgsConstructor — same as in ZipExtractorService (see there).
 * ───────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/api/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    // All dependencies are injected by Spring via the constructor
    // that @RequiredArgsConstructor generates for every `final` field.
    private final RepositoryRepository repositoryRepository;
    private final ZipExtractorService zipExtractorService;
    private final RepositoryStatsService repositoryStatsService;

    /**
     * Triggers the background embedding job after a ZIP is uploaded.
     * @Async in EmbeddingIndexingService means this call returns instantly
     * while embeddings are computed in a background thread.
     */
    private final EmbeddingIndexingService embeddingIndexingService;

    // ═══════════════════════════════════════════════════════════════════════
    // POST /api/repositories/upload
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Accepts a ZIP file, extracts its source files, and returns the new
     * repository's ID and a count of extracted files.
     *
     * @PostMapping("/upload")
     *   — Maps HTTP POST requests to /api/repositories/upload → this method.
     *     POST is the right verb for "create something new" or "send data".
     *
     * @RequestParam("file") MultipartFile zipFile
     *   — Binds the file from the multipart/form-data request.
     *     The client sends the file with form field name "file":
     *       curl -X POST \
     *            -F "file=@my-project.zip" \
     *            http://localhost:8080/api/repositories/upload
     *
     *     MultipartFile is Spring's wrapper around an uploaded file. It gives you:
     *       zipFile.getOriginalFilename() → the original file name on disk
     *       zipFile.getSize()             → size in bytes
     *       zipFile.getInputStream()      → the raw bytes as a stream
     *       zipFile.isEmpty()             → true if no file was sent
     *
     * ResponseEntity<UploadResponseDto>
     *   — ResponseEntity lets you control the HTTP status code AND body.
     *     Without it, Spring always returns 200 OK.
     *     For a creation endpoint, 201 Created is more correct.
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponseDto> uploadZip(
            @RequestParam("file") MultipartFile zipFile) {

        log.info("Received upload request: file='{}', size={} bytes",
                zipFile.getOriginalFilename(), zipFile.getSize());

        // ── Step 1: Derive a clean repository name from the ZIP filename ────
        // "my-project-main.zip" → "my-project-main"
        // If for some reason the filename is null, fall back to "unnamed-repo"
        String repoName = deriveRepoName(zipFile.getOriginalFilename());

        // ── Step 2: Create and save the Repository entity with status=UPLOADING
        // We save it NOW so:
        // a) We have the auto-generated `id` to associate CodeFiles with.
        // b) The user can query the status even while processing is ongoing.
        // @PrePersist on Repository sets uploadedAt and defaults status to UPLOADING.
        Repository repository = Repository.builder()
                .name(repoName)
                .status(RepositoryStatus.UPLOADING)
                .build();

        repository = repositoryRepository.save(repository);
        Long repoId = repository.getId();

        log.info("Created repository id={}, name='{}'", repoId, repoName);

        // ── Step 3: Extract the ZIP and save all CodeFile entities ──────────
        // If this fails, ZipExtractorService throws ZipExtractionException.
        // The @Transactional inside extractAndSave rolls back any partial saves.
        // GlobalExceptionHandler catches the exception and returns HTTP 422.
        //
        // We also update the status to FAILED if an exception occurs, so the
        // repository row reflects the real state.
        int filesExtracted;
        try {
            repository.setStatus(RepositoryStatus.INDEXING);
            repositoryRepository.save(repository); // Persist INDEXING status

            filesExtracted = zipExtractorService.extractAndSave(zipFile, repoId);

            // ── Step 4: Mark the repository as READY ─────────────────────────
            // READY means: files are saved and chunked. Embeddings are still
            // being computed in the background (see Step 5 below).
            repository.setStatus(RepositoryStatus.READY);
            repositoryRepository.save(repository);

            // ── Step 5: Kick off background embedding (fire-and-forget) ──────
            // This call returns IMMEDIATELY because indexEmbeddingsAsync() is
            // annotated with @Async — Spring runs it on a separate thread.
            // The HTTP response below is sent to the user without waiting.
            embeddingIndexingService.indexEmbeddingsAsync(repoId);
            log.info("Background embedding started for repository id={}", repoId);

        } catch (Exception e) {
            // Something went wrong — mark the repo as FAILED so the user
            // knows not to query it. Then rethrow so GlobalExceptionHandler
            // can return the right HTTP error code.
            repository.setStatus(RepositoryStatus.FAILED);
            repositoryRepository.save(repository);
            log.error("Ingestion failed for repository id={}: {}", repoId, e.getMessage());
            throw e; // Re-throw — GlobalExceptionHandler will format the response
        }

        // ── Step 5: Build and return the response DTO ───────────────────────
        // ResponseEntity.status(201).body(...) → HTTP 201 Created + JSON body
        UploadResponseDto response = UploadResponseDto.builder()
                .repositoryId(repoId)
                .name(repoName)
                .status(RepositoryStatus.READY)
                .filesExtracted(filesExtracted)
                .message("Upload successful. " + filesExtracted
                        + " files indexed. Embeddings are being generated in the background.")
                .build();

        log.info("Upload complete for repository id={}. {} files indexed.", repoId, filesExtracted);

        return ResponseEntity
                .status(HttpStatus.CREATED) // HTTP 201 — a new resource was created
                .body(response);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/repositories/{id}
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the current state of a repository.
     * Useful for polling: "is my repo done indexing yet?"
     *
     * @GetMapping("/{id}")
     *   — Maps HTTP GET /api/repositories/5 → this method.
     *
     * @PathVariable Long id
     *   — Extracts the {id} segment from the URL and binds it to the
     *     `id` parameter. So GET /api/repositories/5 → id = 5L.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Repository> getRepository(@PathVariable Long id) {
        Repository repo = repositoryRepository.findById(id)
                // findById returns Optional<Repository>.
                // orElseThrow: if Optional is empty (no row found), throw the exception.
                // GlobalExceptionHandler catches it and returns HTTP 404.
                .orElseThrow(() -> new RepositoryNotFoundException(id));

        return ResponseEntity.ok(repo); // HTTP 200 OK
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/repositories/{id}/stats
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns a statistics snapshot for a repository:
     * total files, language percentages, and counts per architectural type.
     *
     * URL structure note:
     *   /{id}       → @GetMapping("/{id}")        matches /api/repositories/5
     *   /{id}/stats → @GetMapping("/{id}/stats")  matches /api/repositories/5/stats
     *   Both share the same {id} path variable — Spring resolves them independently.
     *
     * The actual computation is in RepositoryStatsService — see that class
     * for a full explanation of how Java Streams are used to compute each metric.
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<RepositoryStatsDto> getStats(@PathVariable Long id) {
        log.info("Stats requested for repository id={}", id);
        // All logic (DB query + stream processing) lives in the service.
        // The controller is intentionally thin — receive, delegate, return.
        RepositoryStatsDto stats = repositoryStatsService.computeStats(id);
        return ResponseEntity.ok(stats);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/repositories
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns all repositories. Useful for a dashboard or list page.
     *
     * No path variable needed — the base mapping "/api/repositories" is used.
     */
    @GetMapping
    public ResponseEntity<List<Repository>> getAllRepositories() {
        List<Repository> repos = repositoryRepository.findAll();
        return ResponseEntity.ok(repos);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE /api/repositories/{id}
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Deletes a repository. Returns 204 No Content (success, but no body).
     *
     * @DeleteMapping("/{id}")
     *   — Maps HTTP DELETE /api/repositories/5 → this method.
     *
     * ResponseEntity<Void>
     *   — Void means the response has no body (just a status code).
     *     HTTP 204 No Content is the convention for successful deletes.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRepository(@PathVariable Long id) {
        if (!repositoryRepository.existsById(id)) {
            throw new RepositoryNotFoundException(id);
        }
        repositoryRepository.deleteById(id);
        log.info("Deleted repository id={}", id);
        return ResponseEntity.noContent().build(); // HTTP 204
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Strips the ".zip" extension from the original filename to use as the
     * repository name.
     *
     * Examples:
     *   "my-project.zip"      → "my-project"
     *   "spring-app-main.zip" → "spring-app-main"
     *   null                  → "unnamed-repo"
     */
    private String deriveRepoName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed-repo";
        }
        // Remove ".zip" suffix (case-insensitive)
        String name = originalFilename.trim();
        if (name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? "unnamed-repo" : name;
    }
}
