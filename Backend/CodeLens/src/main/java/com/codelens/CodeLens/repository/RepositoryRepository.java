package com.codelens.CodeLens.repository;

import com.codelens.CodeLens.model.Repository;
import com.codelens.CodeLens.model.RepositoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
// NOTE: We intentionally do NOT import org.springframework.stereotype.Repository here.
// Our entity class is ALSO named "Repository", so importing the annotation too
// causes a compile error: Java cannot tell which "Repository" you mean.
// Spring Data auto-discovers all JpaRepository subinterfaces without needing @Repository.

import java.util.List;

/**
 * Spring Data JPA repository for the Repository entity.
 *
 * ─── What is JpaRepository? ────────────────────────────────────────────────
 * JpaRepository<Repository, Long> is a Spring interface that gives you
 * a full set of database operations for FREE — no SQL, no implementation.
 *
 *   <Repository> → the Entity class this repo manages
 *   <Long>       → the type of the primary key (@Id field)
 *
 * Methods you get for free just by extending JpaRepository:
 *   save(entity)            → INSERT or UPDATE a row
 *   findById(id)            → SELECT * WHERE id = ?  (returns Optional<Repository>)
 *   findAll()               → SELECT * FROM repository
 *   deleteById(id)          → DELETE WHERE id = ?
 *   existsById(id)          → returns true/false
 *   count()                 → SELECT COUNT(*) FROM repository
 *   ... and many more.
 *
 * ─── How do custom finder methods work? ────────────────────────────────────
 * Spring reads the METHOD NAME and auto-generates the SQL for you.
 * The naming convention is: findBy + FieldName + [Condition]
 *
 * Examples:
 *   findByStatus(RepositoryStatus.READY)
 *     → SELECT * FROM repository WHERE status = 'READY'
 *
 *   findByNameContainingIgnoreCase("boot")
 *     → SELECT * FROM repository WHERE LOWER(name) LIKE '%boot%'
 *
 * You write ZERO SQL. Spring parses the method name and does everything.
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Why is @Repository missing here?
 * ────────────────────────────────
 * Normally you'd add @Repository, but our entity class is ALSO named
 * "Repository" (com.codelens.CodeLens.model.Repository).
 * Importing BOTH the annotation AND our model causes a compile error:
 *   "reference to Repository is ambiguous"
 * Since Spring Data automatically registers all JpaRepository subinterfaces
 * as beans (no annotation needed), we safely omit @Repository here.
 * Exception translation still works — Spring Data enables it by default.
 */
public interface RepositoryRepository extends JpaRepository<Repository, Long> {

    /**
     * Find all repositories currently in a given status.
     * Useful to poll: "which repos are still INDEXING?"
     *
     * Generated SQL: SELECT * FROM repository WHERE status = ?
     */
    List<Repository> findByStatus(RepositoryStatus status);

    /**
     * Find repositories whose name contains the given string (case-insensitive).
     * Useful for a simple search feature.
     *
     * Generated SQL: SELECT * FROM repository WHERE LOWER(name) LIKE LOWER('%keyword%')
     */
    List<Repository> findByNameContainingIgnoreCase(String keyword);

    /**
     * Check whether any repository already has this exact name.
     * Prevents a user uploading the same repo twice with the same name.
     *
     * Generated SQL: SELECT COUNT(*) > 0 FROM repository WHERE name = ?
     */
    boolean existsByName(String name);
}
