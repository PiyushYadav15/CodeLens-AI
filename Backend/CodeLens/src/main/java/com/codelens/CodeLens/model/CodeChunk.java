package com.codelens.CodeLens.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Represents a small, focused "chunk" of a source file.
 *
 * Why do we chunk files instead of storing the whole file?
 * ─────────────────────────────────────────────────────────
 * AI models have a maximum input size (called the "context window").
 * A large Java class might be 500+ lines — too big to send in one go.
 * We split each file into overlapping windows (e.g. 60 lines each,
 * with 10-line overlap) so:
 *  1. Each chunk fits in the AI's context.
 *  2. When the user asks a question, we only send the 3-5 MOST RELEVANT
 *     chunks — not the entire codebase. This is the RAG pattern.
 *  3. The overlap ensures we don't accidentally cut a function in half.
 *
 * Example:
 *   File: UserService.java (200 lines)
 *   Chunk 1: lines  1–60   (the class definition + first methods)
 *   Chunk 2: lines 50–110  (middle methods — 10-line overlap with chunk 1)
 *   Chunk 3: lines 100–160
 *   Chunk 4: lines 150–200
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"content", "embedding"}) // Embeddings are 1536 floats — too big for logs
@Entity
@Table(name = "code_chunk", indexes = {
    // Index on repository_id: fast lookup of "all chunks for repo #5"
    @Index(name = "idx_code_chunk_repository_id", columnList = "repository_id"),
    // Index on file_id: fast lookup of "all chunks for file #42"
    @Index(name = "idx_code_chunk_file_id",       columnList = "file_id")
})
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which file this chunk came from.
     * @Column(name = "file_id") — snake_case convention for DB columns.
     * @Column(nullable = false) — A chunk must always belong to a file.
     */
    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /**
     * Which repository this chunk belongs to.
     * We duplicate this here (it could be derived via fileId → file → repositoryId)
     * as a "denormalization" shortcut. This way we can query
     * "find relevant chunks for repo #5" in a single JOIN-free query,
     * which is much faster at scale.
     */
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    /**
     * The actual text content of this chunk (a slice of the source file).
     *
     * @Column(columnDefinition = "TEXT")
     *   TEXT type — same reason as CodeFile.content: VARCHAR(255) is too small
     *   for code. A 60-line Java method can easily exceed 255 characters.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * The line number in the original file where this chunk starts.
     * Used to generate source citations like "UserService.java : lines 45–105".
     */
    @Column(nullable = false)
    private Integer startLine;

    /**
     * The line number where this chunk ends (inclusive).
     */
    @Column(nullable = false)
    private Integer endLine;

    /**
     * The name of the Java class (or Python class, etc.) this chunk is inside.
     * Nullable — some files have no class (e.g. a script, a config file).
     *
     * length = 255 — class names are short strings, 255 is plenty.
     */
    @Column(length = 255, nullable = true)
    private String className;

    /**
     * The name of the method this chunk is inside, if any.
     * Nullable — a chunk might span the class-level field declarations,
     * not a specific method.
     */
    @Column(length = 255, nullable = true)
    private String methodName;

    /**
     * The AI-generated embedding vector, stored as a JSON string.
     *
     * What is an embedding?
     * ─────────────────────
     * An embedding is a list of floating-point numbers (e.g. 1536 of them)
     * that captures the *semantic meaning* of the text. Two chunks that
     * are semantically similar (e.g. two authentication functions) will
     * have embedding vectors that are mathematically "close" to each other.
     *
     * We store it as a JSON string like: "[0.12, -0.84, 0.03, ...]"
     * because H2 / standard SQL has no native "float array" type.
     * When we need to do similarity search, we parse this string back
     * into a float[] or List<Double> and compute cosine similarity in Java.
     *
    /**
     * The AI-generated embedding vector stored natively in PostgreSQL using pgvector.
     *
     * ─────────────────────────────────────────────────────────────────────────
     *  PostgreSQL + pgvector Integration (Beginner-Friendly Approach)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * • columnDefinition = "vector(768)"
     *     Tells PostgreSQL to create the column with pgvector's 768-dimensional
     *     vector type (matching Gemini text-embedding-004).
     *
     * • @ColumnTransformer:
     *     write = "CAST(? AS vector)"  -> On INSERT/UPDATE, casts the Java String
     *                                     "[0.012,-0.44,...]" to PostgreSQL's vector type.
     *     read  = "CAST(embedding AS text)" -> On SELECT, casts vector back to text so
     *                                     Hibernate reads it directly into Java String.
     *
     * This avoids needing any external pgvector Java dependencies or complex
     * type descriptors, while keeping 100% compatibility with your existing code.
     */
    // Stored as plain JSON TEXT � no pgvector needed.
    // e.g. "[0.012,-0.44,0.003,...]"  Similarity search is done in Java (cosine distance).
    @Column(name = "embedding", columnDefinition = "TEXT", nullable = true)
    private String embedding;
}
