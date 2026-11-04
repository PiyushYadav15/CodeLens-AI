package com.codelens.CodeLens.ingestion;

import com.codelens.CodeLens.exception.ZipExtractionException;
import com.codelens.CodeLens.model.CodeChunk;
import com.codelens.CodeLens.model.CodeFile;
import com.codelens.CodeLens.repository.CodeChunkRepository;
import com.codelens.CodeLens.repository.CodeFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles everything related to unpacking an uploaded ZIP file and
 * turning each source file inside it into a saved CodeFile entity.
 *
 * ─── How ZipInputStream Works (Beginner Explanation) ──────────────────────
 *
 * A ZIP file is a container — think of it as a folder that has been
 * compressed and packed into a single file. Inside, there are "entries":
 * each entry is either a file or a directory.
 *
 * java.util.zip.ZipInputStream lets you READ a ZIP file entry-by-entry
 * without extracting it to disk first (everything stays in memory).
 *
 * The reading loop works like this:
 *
 *   ZipInputStream zis = new ZipInputStream(inputStream);
 *   ZipEntry entry;
 *
 *   while ((entry = zis.getNextEntry()) != null) {
 *       // entry.getName()      → "src/main/java/Foo.java" (the file path)
 *       // entry.isDirectory()  → true if this is a folder, not a file
 *       // entry.getSize()      → file size in bytes (-1 if unknown)
 *
 *       // Read the bytes of the current entry:
 *       byte[] bytes = zis.readAllBytes();  // Java 9+
 *
 *       zis.closeEntry(); // Move to the next entry
 *   }
 *
 * KEY RULES for ZipInputStream:
 * 1. You MUST call getNextEntry() before you can read bytes.
 * 2. You read the bytes of the CURRENT entry using zis itself (not entry).
 * 3. Calling getNextEntry() again automatically closes the current entry.
 * 4. The stream is forward-only — you can't go back to a previous entry.
 * ───────────────────────────────────────────────────────────────────────────
 *
 * @Service
 *   — Marks this class as a Spring "service" bean.
 *     Spring creates one instance of it and makes it available for injection
 *     anywhere in the app via @Autowired or constructor injection.
 *     It's functionally identical to @Component, but the name "service"
 *     communicates that this class contains business logic.
 *
 * @Slf4j (Lombok)
 *   — Gives us a `log` field for logging. We use it to print what's
 *     happening without cluttering the main logic with System.out.println.
 *
 * @RequiredArgsConstructor (Lombok)
 *   — Generates a constructor for every `final` field.
 *     Spring sees a single constructor and automatically injects the
 *     matching beans — this is called "constructor injection" and it's
 *     the recommended way to inject dependencies (vs @Autowired on a field).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZipExtractorService {

    // @RequiredArgsConstructor generates a constructor for ALL final fields.
    // Spring sees one constructor → automatically injects all three beans.
    private final CodeFileRepository codeFileRepository;

    /**
     * Does the content-based classification and endpoint extraction.
     * Injected here so ZipExtractorService stays focused on ZIP I/O
     * and delegates all classification logic to this dedicated service.
     * (Single Responsibility Principle — each class has one job.)
     */
    private final FileClassifierService fileClassifierService;

    /**
     * Splits each saved CodeFile into Chunks and saves them as CodeChunk entities.
     * Injected here so the chunking logic stays in a dedicated, testable class.
     */
    private final ChunkingService chunkingService;

    /**
     * Saves the CodeChunk entities produced by ChunkingService.
     * Part of the same @Transactional context as the rest of extractAndSave().
     */
    private final CodeChunkRepository codeChunkRepository;

    // ── Configuration constants ─────────────────────────────────────────────

    /**
     * Folder name segments that we skip entirely.
     * If an entry's path CONTAINS any of these as a path segment, we ignore it.
     *
     * Why skip these?
     * - node_modules/ → thousands of dependency files; not user code
     * - target/ build/ dist/ → compiled output, not source
     * - .git/ .idea/ .vscode/ → version control / IDE metadata
     *
     * Set.of(...) creates an immutable Set — slightly faster lookup than List
     * because Set.contains() is O(1) (hash-based) vs List.contains() O(n).
     */
    private static final Set<String> SKIP_FOLDERS = Set.of(
            "node_modules", "target", "build", ".git", ".idea", "dist", ".vscode"
    );

    /**
     * File extensions we want to keep and index.
     * Only these extensions will become CodeFile entities.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java", ".jsx", ".js", ".ts", ".tsx",
            ".html", ".css",
            ".json", ".yml", ".yaml", ".xml",
            ".sql", ".md", ".properties"
    );

    /**
     * Skip files larger than 1 MB. Very large files are usually generated
     * (e.g. a minified bundle.js or a massive SQL dump) and are not useful
     * for code understanding. They'd also waste AI tokens.
     */
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024; // 1 MB

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Main entry point: extract a ZIP and save all relevant files to the DB.
     *
     * @param zipFile      The ZIP uploaded by the user (Spring MultipartFile)
     * @param repositoryId The ID of the Repository entity we already saved
     * @return             The number of files that were extracted and saved
     *
     * @Transactional
     *   — Wraps this method in a database transaction.
     *     If anything throws an exception AFTER we've saved some CodeFiles,
     *     Spring automatically ROLLS BACK all the saves — the DB returns to
     *     its state before this method was called.
     *     Without @Transactional, a mid-way failure would leave partial data.
     */
    @Transactional
    public int extractAndSave(MultipartFile zipFile, Long repositoryId) {

        // Validate that we actually received a file
        if (zipFile == null || zipFile.isEmpty()) {
            throw new ZipExtractionException("Uploaded file is empty.");
        }

        log.info("Starting ZIP extraction for repository id={}, file='{}', size={} bytes",
                repositoryId, zipFile.getOriginalFilename(), zipFile.getSize());

        int savedCount = 0; // Tracks how many files we successfully save

        // ── Step 1: Open the ZIP stream ──────────────────────────────────────
        //
        // zipFile.getInputStream() → gives us the raw bytes of the uploaded file
        // as a stream (we don't need to save it to disk first).
        //
        // try-with-resources (the "try (ZipInputStream zis = ...) { }" syntax):
        //   This is Java's way of automatically closing a stream when we're done,
        //   even if an exception occurs. It's equivalent to a finally { zis.close() }
        //   block, but shorter and safer. Always use this with streams.
        try (InputStream rawStream = zipFile.getInputStream();
             ZipInputStream zis = new ZipInputStream(rawStream, StandardCharsets.UTF_8)) {
            // StandardCharsets.UTF_8: tells Java that file names inside the ZIP
            // are encoded in UTF-8. This prevents garbled names with special chars.

            // ── Step 2: Loop through every entry in the ZIP ──────────────────
            ZipEntry entry; // Represents one file or folder inside the ZIP

            //noinspection NestedAssignment — standard ZipInputStream pattern
            while ((entry = zis.getNextEntry()) != null) {
                // getNextEntry() returns the NEXT entry and positions the stream
                // to read its bytes. Returns null when there are no more entries.

                // ── Step 3: Skip directories ─────────────────────────────────
                // ZIP entries include both files AND directory entries.
                // Directory entries have no content, so we skip them.
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue; // `continue` jumps back to the top of the while loop
                }

                String entryName = entry.getName();
                // entryName looks like: "my-project/src/main/java/Foo.java"
                // Note: some ZIPs have a top-level folder, some don't.

                // ── Step 4: Skip unwanted folders ────────────────────────────
                if (isInSkippedFolder(entryName)) {
                    log.debug("Skipping (excluded folder): {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                // ── Step 5: Skip unwanted file extensions ─────────────────────
                if (!hasAllowedExtension(entryName)) {
                    log.debug("Skipping (unsupported extension): {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                // ── Step 6: Read the file bytes ───────────────────────────────
                // readAllBytes() reads the ENTIRE content of the current ZIP entry.
                // This is safe here because we'll filter out huge files next.
                byte[] bytes = zis.readAllBytes();

                // ── Step 7: Skip very large files ─────────────────────────────
                if (bytes.length > MAX_FILE_SIZE_BYTES) {
                    log.warn("Skipping (too large, {} bytes): {}", bytes.length, entryName);
                    zis.closeEntry();
                    continue;
                }

                // ── Step 8: Skip empty files ──────────────────────────────────
                if (bytes.length == 0) {
                    log.debug("Skipping (empty file): {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                // ── Step 9: Convert bytes → String ───────────────────────────
                // new String(bytes, StandardCharsets.UTF_8)
                //   Interprets the raw bytes as UTF-8 text. This covers
                //   virtually all source code files.
                String content = new String(bytes, StandardCharsets.UTF_8);

                // ── Step 10: Normalise the file path ─────────────────────────
                // Many ZIPs wrap everything in a top-level folder like "my-repo-main/".
                // We strip that prefix so all paths start from the project root.
                // E.g. "my-repo-main/src/Foo.java" → "src/Foo.java"
                String normalizedPath = stripTopLevelFolder(entryName);

                // ── Step 11: Classify the file (content-based, with regex) ────
                // classify() checks annotations (@RestController, @Service, etc.)
                // and for controllers also extracts endpoint mappings.
                // We only need the FileType here; endpoints are logged for now
                // and will be stored in a future step.
                ClassificationResult classification =
                        fileClassifierService.classify(normalizedPath, content);

                // ── Step 12: Build and save the CodeFile entity ───────────────
                CodeFile codeFile = CodeFile.builder()
                        .repositoryId(repositoryId)
                        .path(normalizedPath)
                        .content(content)
                        .language(detectLanguage(normalizedPath))
                        .fileType(classification.fileType())
                        .sizeBytes((long) bytes.length)
                        .build();

                codeFileRepository.save(codeFile);
                savedCount++;

                // ── Step 13: Chunk the file and save CodeChunk entities ────────
                // chunkAndSave() picks the right strategy (Java/Markdown/generic)
                // based on the file extension, then persists every chunk.
                chunkAndSave(codeFile, normalizedPath, content);

                log.debug("Saved: {} ({} bytes)", normalizedPath, bytes.length);

                // closeEntry() tells ZipInputStream we are done with this entry
                // and it can move to the next one. getNextEntry() does this
                // implicitly too, but being explicit is good practice.
                zis.closeEntry();
            }

        } catch (IOException e) {
            // IOException is thrown if the file is corrupt, not a ZIP, or
            // if the network stream is interrupted during upload.
            // We wrap it in our custom exception (which returns HTTP 422).
            throw new ZipExtractionException(
                    "Failed to read ZIP file: " + e.getMessage(), e);
        }

        log.info("ZIP extraction complete for repository id={}. Saved {} files.",
                repositoryId, savedCount);
        return savedCount;
    }

    // ── Private helper methods ──────────────────────────────────────────────

    /**
     * Returns true if the entry path contains any of the folders we skip.
     *
     * We split the path by "/" and check if any SEGMENT matches.
     * This avoids false positives: a path like "my-target-service/Foo.java"
     * should NOT be skipped just because "target" appears in the parent folder name.
     * But "target/classes/Foo.class" SHOULD be skipped.
     *
     * Example:
     *   path = "my-app/target/classes/Foo.class"
     *   parts = ["my-app", "target", "classes", "Foo.class"]
     *   SKIP_FOLDERS contains "target" → returns true
     */
    private boolean isInSkippedFolder(String path) {
        // Split path on "/" — this handles both Unix and Windows-style ZIPs
        String[] parts = path.split("/");
        for (String part : parts) {
            if (SKIP_FOLDERS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the file name ends with one of our allowed extensions.
     *
     * lastIndexOf('.') finds the position of the last dot in the name.
     * substring(dotIndex) extracts from the dot onwards: ".java", ".ts", etc.
     *
     * Example:
     *   "src/UserService.java" → dotIndex=18 → extension=".java" → true
     *   "src/compiled.class"   → extension=".class" → false
     *   "Makefile"             → dotIndex=-1 → no extension → false
     */
    private boolean hasAllowedExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0) return false; // No extension at all
        String extension = path.substring(dotIndex).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(extension);
    }

    /**
     * Many ZIP files wrap everything in a top-level folder:
     *   "my-project-main/src/Foo.java"    ← GitHub "Download ZIP" format
     *   "my-project/src/Foo.java"
     *
     * We strip that first segment so paths start from the project root:
     *   "src/Foo.java"
     *
     * But if the path has no "/" (it's already at the root), we keep it as-is.
     */
    private String stripTopLevelFolder(String path) {
        int firstSlash = path.indexOf('/');
        if (firstSlash < 0 || firstSlash == path.length() - 1) {
            // No slash, or slash is the very last character → no folder to strip
            return path;
        }
        return path.substring(firstSlash + 1);
    }

    /**
     * Maps file extensions to programming language names.
     * This is a simple lookup — not perfect, but good enough for display
     * and basic filtering in the RAG phase.
     */
    private String detectLanguage(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "unknown";
        return switch (path.substring(dot).toLowerCase()) {
            case ".java"                 -> "java";
            case ".js"                   -> "javascript";
            case ".ts"                   -> "typescript";
            case ".jsx"                  -> "javascript";
            case ".tsx"                  -> "typescript";
            case ".html"                 -> "html";
            case ".css"                  -> "css";
            case ".json"                 -> "json";
            case ".yml", ".yaml"         -> "yaml";
            case ".xml"                  -> "xml";
            case ".sql"                  -> "sql";
            case ".md"                   -> "markdown";
            case ".properties"           -> "properties";
            default                      -> "unknown";
        };
    }

    // detectFileType() has been removed.
    // Classification is now handled entirely by FileClassifierService,
    // which checks content annotations first (more reliable) then falls
    // back to path heuristics. See FileClassifierService.java.

    // ── Chunking helper ────────────────────────────────────────────────

    /**
     * Chunks a saved CodeFile and persists each chunk as a CodeChunk entity.
     *
     * Flow:
     *   1. Ask ChunkingService to split the content (returns List<Chunk>)
     *   2. For each Chunk, build a CodeChunk entity and save it
     *
     * This runs inside the same @Transactional transaction as extractAndSave(),
     * so if any chunk save fails the ENTIRE upload is rolled back — no half-
     * ingested repositories in the database.
     *
     * @param codeFile The already-saved CodeFile entity (we need its id)
     * @param path     The file’s normalised path (used to choose chunk strategy)
     * @param content  The file’s full text content
     */
    private void chunkAndSave(CodeFile codeFile, String path, String content) {
        // Delegate to ChunkingService — it returns a List<Chunk> (plain records)
        List<Chunk> chunks = chunkingService.chunk(path, content);

        for (Chunk chunk : chunks) {
            CodeChunk codeChunk = CodeChunk.builder()
                    .fileId(codeFile.getId())           // FK to the parent CodeFile
                    .repositoryId(codeFile.getRepositoryId()) // denormalised FK
                    .content(chunk.content())           // the chunk’s text
                    .startLine(chunk.startLine())       // for source citations
                    .endLine(chunk.endLine())
                    .methodName(chunk.methodName())     // null for non-Java chunks
                    // className and embedding are left null here:
                    //   className — we’ll extract it in a future step
                    //   embedding — set by EmbeddingService (next phase)
                    .build();

            codeChunkRepository.save(codeChunk);
        }

        log.debug("  → {} chunks saved for file id={} ({})",
                chunks.size(), codeFile.getId(), path);
    }
}
