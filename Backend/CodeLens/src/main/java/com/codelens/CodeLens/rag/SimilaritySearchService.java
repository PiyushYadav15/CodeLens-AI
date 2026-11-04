package com.codelens.CodeLens.rag;

import com.codelens.CodeLens.model.CodeChunk;
import com.codelens.CodeLens.repository.CodeChunkRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Service responsible for finding the most semantically relevant code chunks
 * for a user's question using Cosine Similarity.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  HOW SIMILARITY SEARCH WORKS (In Plain English)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 1. An embedding is an array of numbers (e.g., 768 numbers from Gemini).
 *    Think of each number as coordinates in a high-dimensional space.
 * 2. When a user asks a question like "Where is user authentication handled?",
 *    we turn their question into a vector: queryEmbedding = [0.12, -0.45, ...]
 * 3. Every CodeChunk in our database already has its own embedding stored as a
 *    JSON string: "[0.15, -0.42, ...]"
 * 4. We compare the query vector against every chunk vector using Cosine Similarity.
 * 5. We sort the chunks from highest similarity to lowest, and take the top K (e.g., top 5).
 * 6. Those top K chunks contain the exact code most relevant to the question!
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  BRUTE-FORCE VS. VECTOR DATABASES (Why this is fine for CodeLens)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * • What we are doing here is "Exact / Brute-Force K-Nearest Neighbors":
 *   We iterate through all chunks for a repository one by one in memory and
 *   compute the cosine similarity for each.
 *
 * • Why this is totally fine for projects like this:
 *   - A typical code repository has between 100 and 5,000 code chunks.
 *   - Computing cosine similarity on 768-dimensional float arrays takes mere
 *     microseconds in modern Java (a few milliseconds to scan 5,000 chunks).
 *   - Zero extra infrastructure: no external vector DB server (Pinecone,
 *     Milvus, Qdrant, Weaviate, pgvector) to install, host, or pay for.
 *
 * • When would you need a dedicated Vector Database?
 *   - If you index millions or billions of code chunks across an entire company.
 *   - Dedicated vector DBs use Approximate Nearest Neighbor (ANN) algorithms
 *     (like HNSW - Hierarchical Navigable Small World graphs or IVF) that index
 *     vectors into spatial clusters. They don't check every vector; they jump
 *     straight to the nearest cluster in O(log N) time.
 *   - But for thousands of chunks in a single repo, in-memory linear scan is
 *     faster, simpler, and 100% accurate (no approximation errors).
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilaritySearchService {

    private final CodeChunkRepository codeChunkRepository;
    private final ObjectMapper objectMapper;

    /**
     * Parses a JSON string representation of a float array back into a Java float[] array.
     *
     * Example input:  "[0.012, -0.44, 0.003]"
     * Example output: float[] { 0.012f, -0.44f, 0.003f }
     *
     * Jackson's ObjectMapper can directly deserialize into a float[] class.
     *
     * @param embeddingJson The JSON array string stored in the database
     * @return float[] array of numbers
     * @throws JsonProcessingException if the JSON string cannot be parsed as float[]
     */
    public float[] parseEmbedding(String embeddingJson) throws JsonProcessingException {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            throw new IllegalArgumentException("Embedding JSON cannot be null or blank");
        }
        return objectMapper.readValue(embeddingJson, float[].class);
    }

    /**
     * Computes the Cosine Similarity between two vectors of identical dimensions.
     *
     * ─── WHAT IS COSINE SIMILARITY? (The Simple Math) ────────────────────────
     * Cosine similarity measures the angle between two multi-dimensional arrows (vectors):
     *
     *   cos(θ) = (A · B) / (||A|| * ||B||)
     *
     *   1. Dot Product (A · B):
     *      Multiply matching elements and sum them:
     *      (a[0]*b[0] + a[1]*b[1] + ... + a[n]*b[n])
     *
     *   2. Magnitude / Norm (||A||):
     *      The geometric length of vector A:
     *      sqrt(a[0]^2 + a[1]^2 + ... + a[n]^2)
     *
     *   3. Division:
     *      Divide the dot product by (magnitudeA * magnitudeB).
     *
     * What the score means:
     *   • 1.0  → Vectors point in the exact same direction (identical semantic meaning).
     *   • 0.0  → Vectors are orthogonal / 90 degrees apart (completely unrelated topics).
     *   • -1.0 → Vectors point in opposite directions (rare in text embeddings).
     * ─────────────────────────────────────────────────────────────────────────
     *
     * @param a The first embedding vector (e.g., query embedding)
     * @param b The second embedding vector (e.g., chunk embedding)
     * @return Cosine similarity score between -1.0 and 1.0 (typically 0.0 to 1.0 for embeddings)
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Vectors cannot be null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector dimensions must match! Vector A: " + a.length + ", Vector B: " + b.length);
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        // Check for edge case: zero-length vectors to avoid dividing by zero (NaN)
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Finds the top K most similar CodeChunks for a given repository using
     * PostgreSQL pgvector's native <=> (cosine distance) operator.
     *
     * ─── BEFORE VS AFTER: WHAT CHANGED? ──────────────────────────────────────
     * Before (Java In-Memory Scan):
     *   1. Loaded ALL chunks for the repo into Java RAM over JDBC (network transfer).
     *   2. Ran a Java CPU loop to compute cosine similarity for every chunk.
     *   3. Sorted all chunks in Java memory and sliced the top K.
     *
     * After (pgvector Native SQL Search):
     *   1. We format queryEmbedding float[] -> "[0.012, -0.44, ...]" string.
     *   2. PostgreSQL does the vector distance math directly in C inside the database.
     *   3. If an HNSW or IVFFlat index exists, PostgreSQL finds matches in O(log N) time.
     *   4. Only the top K matching rows are transferred over JDBC back to Java!
     * ─────────────────────────────────────────────────────────────────────────
     *
     * @param queryEmbedding The embedding of the user's question
     * @param repositoryId   The repository to search within
     * @param k              The maximum number of chunks to return (e.g., 6)
     * @return List of ScoredChunk objects ranked from most similar to least similar
     */
    public List<ScoredChunk> topKSimilar(float[] queryEmbedding, Long repositoryId, int k) {
        if (queryEmbedding == null || repositoryId == null || k <= 0) {
            return Collections.emptyList();
        }

        // In-memory cosine similarity search (no pgvector required).
        // Loads all embedded chunks for the repo and ranks them by cosine similarity.
        List<CodeChunk> allChunks = codeChunkRepository.findEmbeddedChunksByRepositoryId(repositoryId);

        if (allChunks.isEmpty()) {
            log.warn("No embedded chunks found for repository id: {}", repositoryId);
            return Collections.emptyList();
        }

        log.debug("Running in-memory similarity search over {} chunks for repoId={}", allChunks.size(), repositoryId);

        return allChunks.stream()
                .map(chunk -> {
                    double score = 0.0;
                    try {
                        float[] chunkVector = parseEmbedding(chunk.getEmbedding());
                        score = cosineSimilarity(queryEmbedding, chunkVector);
                    } catch (Exception e) {
                        log.warn("Could not parse embedding for chunk id {}: {}", chunk.getId(), e.getMessage());
                    }
                    return new ScoredChunk(chunk, score);
                })
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(k)
                .toList();
    }
}
