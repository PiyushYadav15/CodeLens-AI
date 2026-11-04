package com.codelens.CodeLens.ingestion;

/**
 * An intermediate, lightweight result from the ChunkingService.
 *
 * This is NOT the CodeChunk database entity. It's a pure in-memory
 * data holder that ChunkingService returns. ZipExtractorService then
 * maps these into CodeChunk entities and saves them to the database.
 *
 * Why a separate record and not reuse CodeChunk directly?
 * ────────────────────────────────────────────────────────
 * CodeChunk is a JPA entity — it has @Id, @Column, and other database
 * concerns baked in. ChunkingService is pure logic; it shouldn't know
 * about databases at all. Separating them respects the principle:
 *   "A chunker's job is to split text, not to talk to a database."
 *
 * Fields:
 *   content   — the actual text of this chunk
 *   startLine — 1-indexed line in the original file where the chunk begins
 *   endLine   — 1-indexed line in the original file where the chunk ends
 *   methodName — extracted method name if this is a Java method chunk (nullable)
 */
public record Chunk(
        String content,
        int startLine,
        int endLine,
        String methodName   // null for non-Java or file-level chunks
) {
    /**
     * Factory for chunks that don't correspond to a named method
     * (generic splits, markdown sections, whole-file fallback).
     */
    public static Chunk of(String content, int startLine, int endLine) {
        return new Chunk(content, startLine, endLine, null);
    }
}
