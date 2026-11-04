package com.codelens.CodeLens.repository;

import com.codelens.CodeLens.model.CodeFile;
import com.codelens.CodeLens.model.FileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the CodeFile entity.
 *
 * Extends JpaRepository<CodeFile, Long>:
 *   CodeFile → the entity being managed
 *   Long     → type of the @Id primary key
 *
 * All standard CRUD operations (save, findById, findAll, deleteById, …)
 * are inherited for free. The methods below are CUSTOM finders — Spring
 * auto-generates the SQL from the method name. See RepositoryRepository
 * for a detailed explanation of how this naming convention works.
 */
@Repository
public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {

    /**
     * Get all source files that belong to a specific repository.
     *
     * This is the most common query in the ingestion and RAG phases.
     * Generated SQL: SELECT * FROM code_file WHERE repository_id = ?
     */
    List<CodeFile> findByRepositoryId(Long repositoryId);

    /**
     * Get all files of a specific architectural type in a repository.
     * E.g. find all SERVICE files to look for business logic.
     *
     * Generated SQL: SELECT * FROM code_file
     *                WHERE repository_id = ? AND file_type = ?
     */
    List<CodeFile> findByRepositoryIdAndFileType(Long repositoryId, FileType fileType);

    /**
     * Find a file by its exact path within a repository.
     * Useful to avoid re-processing a file that was already ingested.
     *
     * Returns Optional<CodeFile> because the file may not exist yet.
     * Optional forces you to handle the "not found" case explicitly,
     * preventing NullPointerExceptions.
     *
     * Generated SQL: SELECT * FROM code_file
     *                WHERE repository_id = ? AND path = ?
     *                LIMIT 1
     */
    Optional<CodeFile> findByRepositoryIdAndPath(Long repositoryId, String path);

    /**
     * Get all files of a specific programming language in a repository.
     * E.g. only "java" files, or only "python" files.
     *
     * Generated SQL: SELECT * FROM code_file
     *                WHERE repository_id = ? AND language = ?
     */
    List<CodeFile> findByRepositoryIdAndLanguage(Long repositoryId, String language);

    /**
     * Count how many files belong to a repository.
     * Useful for the project dashboard: "42 files indexed".
     *
     * Generated SQL: SELECT COUNT(*) FROM code_file WHERE repository_id = ?
     */
    long countByRepositoryId(Long repositoryId);

    /**
     * Delete all files belonging to a repository.
     * Used when a repository is deleted or re-uploaded from scratch.
     *
     * Generated SQL: DELETE FROM code_file WHERE repository_id = ?
     */
    void deleteByRepositoryId(Long repositoryId);
}
