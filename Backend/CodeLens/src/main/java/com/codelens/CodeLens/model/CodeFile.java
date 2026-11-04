package com.codelens.CodeLens.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Represents a single source file extracted from an uploaded ZIP.
 *
 * Example: the file "src/main/java/com/example/UserService.java"
 * from the uploaded "my-project.zip" becomes one CodeFile row.
 *
 * One Repository → Many CodeFiles   (one-to-many relationship)
 * One CodeFile   → Many CodeChunks  (one-to-many relationship)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "content") // Exclude content from logs — it can be megabytes long
@Entity
@Table(name = "code_file", indexes = {
    // @Table indexes — pre-build a DB index on repositoryId.
    // Without an index, "find all files for repo #5" scans EVERY row.
    // With an index, the DB jumps straight to the matching rows.
    // name = name of the index in the DB, columnList = column(s) to index.
    @Index(name = "idx_code_file_repository_id", columnList = "repository_id")
})
public class CodeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The ID of the Repository this file belongs to.
     *
     * We store a plain Long (foreign key) rather than a full @ManyToOne
     * relationship object. This is intentional for beginners:
     * - Simpler: no lazy-loading pitfalls or circular serialization issues.
     * - Easier to query: "find files where repositoryId = 5".
     * - You can always add the full @ManyToOne relationship later.
     *
     * @Column(name = "repository_id") — The actual column name in the DB table.
     *   By default Lombok/JPA would name it "repositoryId" (camelCase),
     *   but database convention is snake_case: "repository_id".
     * @Column(nullable = false) — A file must always belong to a repository.
     */
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    /**
     * The file's path inside the ZIP, relative to the root.
     * E.g.: "src/main/java/com/example/UserService.java"
     *
     * @Column(nullable = false) — A file must always have a path.
     * length = 1024 — Paths can be long; default column length is 255 chars.
     */
    @Column(nullable = false, length = 1024)
    private String path;

    /**
     * The programming language of this file.
     * E.g.: "java", "python", "javascript", "typescript"
     *
     * We store this as a plain String (not an enum) because there are too
     * many possible languages to enumerate, and new ones appear regularly.
     */
    @Column(length = 50)
    private String language;

    /**
     * The architectural role of this file (CONTROLLER, SERVICE, etc.)
     * See the FileType enum for full documentation.
     *
     * @Enumerated(EnumType.STRING) — Stores the enum's *name* ("SERVICE"),
     *   not its ordinal number (1). Always use STRING — see Repository.java
     *   for the full explanation of why.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType fileType;

    /**
     * The full raw text content of the file.
     *
     * @Column(columnDefinition = "TEXT")
     *   The default JPA column type for String is VARCHAR(255) — max 255 chars.
     *   Source files are easily tens of thousands of characters.
     *   columnDefinition = "TEXT" tells the database to use its TEXT type
     *   instead, which can hold up to 65,535 bytes (MySQL) or unlimited (H2/Postgres).
     *   nullable = true — some files (e.g. empty __init__.py) may have no content.
     */
    @Column(columnDefinition = "TEXT", nullable = true)
    private String content;

    /**
     * Size of the file in bytes.
     * Useful for filtering out very large files before calling the AI.
     */
    @Column
    private Long sizeBytes;
}
