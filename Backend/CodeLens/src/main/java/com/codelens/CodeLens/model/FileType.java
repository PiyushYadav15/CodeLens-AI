package com.codelens.CodeLens.model;

/**
 * Categorises the architectural role of a source file.
 * This helps the RAG layer focus searches — e.g. if the user asks
 * "where is my database logic?" we can prioritise REPOSITORY_LAYER files.
 *
 * CONTROLLER        → Handles HTTP requests (@RestController, @Controller)
 * SERVICE           → Contains business logic (@Service)
 * REPOSITORY_LAYER  → Talks to the database (@Repository, JPA repos)
 *                     Named REPOSITORY_LAYER (not REPOSITORY) to avoid
 *                     confusion with our com.codelens.model.Repository entity.
 * ENTITY            → A database table represented as a Java class (@Entity)
 * REACT_COMPONENT   → A React (.jsx / .tsx) UI component
 * OTHER             → Config files, utilities, tests, build scripts, etc.
 */
public enum FileType {
    CONTROLLER,
    SERVICE,
    REPOSITORY_LAYER,
    ENTITY,
    REACT_COMPONENT,
    OTHER
}
