package com.codelens.CodeLens.repository;

import com.codelens.CodeLens.model.CodeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the CodeChunk entity.
 *
 * This is the most query-intensive repository in the whole app.
 * During the RAG (question-answering) phase, we fetch ALL chunks for a
 * repository, compare their embeddings to the question's embedding, and
 * return the top-N most similar chunks. Performance matters here.
 */
@Repository
public interface CodeChunkRepository extends JpaRepository<CodeChunk, Long> {

    /**
     * Fetch all chunks that belong to a specific repository.
     *
     * This is used in the similarity search: load all chunk embeddings
     * into memory, then find the closest ones to the query vector.
     *
     * Generated SQL: SELECT * FROM code_chunk WHERE repository_id = ?
     */
    List<CodeChunk> findByRepositoryId(Long repositoryId);

    /**
     * Fetch all chunks that came from a specific file.
     * Useful for debugging: "show me how this file was chunked."
     *
     * Generated SQL: SELECT * FROM code_chunk WHERE file_id = ?
     */
    List<CodeChunk> findByFileId(Long fileId);

    /**
     * Fetch only chunks that HAVE an embedding (i.e. the embedding is not null).
     * During the embedding phase some chunks may still be waiting to be
     * vectorized. This finder lets you process them in the right order.
     *
     * NOTE: Spring Data cannot auto-derive "IS NOT NULL" purely from the
     * method name in all versions, so we use a JPQL @Query here.
     *
     * ─── What is @Query? ────────────────────────────────────────────────────
     * @Query lets you write JPQL (Java Persistence Query Language) when the
     * auto-generated method name becomes awkward or ambiguous.
     * JPQL looks like SQL but uses *entity class names* and *field names*
     * instead of table/column names.
     *
     *   "c" is an alias for CodeChunk
     *   "c.embedding IS NOT NULL" checks the Java field, not the DB column
     *   @Param("repoId") binds the :repoId placeholder to the method arg
     * ─────────────────────────────────────────────────────────────────────────
     */
    @Query("SELECT c FROM CodeChunk c WHERE c.repositoryId = :repoId AND c.embedding IS NOT NULL")
    List<CodeChunk> findEmbeddedChunksByRepositoryId(@Param("repoId") Long repositoryId);

    // Similarity search is done in Java � see SimilaritySearchService.topKSimilar()

    /**
     * Fetch chunks that do NOT yet have an embedding.
     * Used by the EmbeddingService to find work to do:
     * "give me all un-embedded chunks so I can vectorize them."
     *
     * Generated SQL: SELECT * FROM code_chunk
     *                WHERE repository_id = ? AND embedding IS NULL
     */
    List<CodeChunk> findByRepositoryIdAndEmbeddingIsNull(Long repositoryId);

    /**
     * Count how many chunks in a repo are still missing their embedding.
     * Useful to show progress: "3 / 47 chunks still being processed."
     *
     * Generated SQL: SELECT COUNT(*) FROM code_chunk
     *                WHERE repository_id = ? AND embedding IS NULL
     */
    long countByRepositoryIdAndEmbeddingIsNull(Long repositoryId);

    /**
     * Count the total number of chunks for a repository.
     *
     * Generated SQL: SELECT COUNT(*) FROM code_chunk WHERE repository_id = ?
     */
    long countByRepositoryId(Long repositoryId);

    /**
     * Delete all chunks belonging to a repository.
     * Used when re-ingesting a repo from scratch.
     *
     * Generated SQL: DELETE FROM code_chunk WHERE repository_id = ?
     */
    void deleteByRepositoryId(Long repositoryId);

    /**
     * Delete all chunks that came from a specific file.
     * Used when a single file is re-processed after a change.
     *
     * Generated SQL: DELETE FROM code_chunk WHERE file_id = ?
     */
    void deleteByFileId(Long fileId);
}
