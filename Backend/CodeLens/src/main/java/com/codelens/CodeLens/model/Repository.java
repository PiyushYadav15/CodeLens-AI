package com.codelens.CodeLens.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Represents a single uploaded code repository (the ZIP the user sent us).
 *
 * This class is a JPA Entity — Spring will automatically create a database
 * table called "repository" with one column for each field below.
 */

// ── Lombok annotations ────────────────────────────────────────────────────

/**
 * @Getter  — Lombok generates a public getXxx() method for every field.
 *            Without this you'd have to write ~10 lines of boilerplate.
 * @Setter  — Lombok generates a public setXxx() method for every field.
 * @Builder — Adds a fluent builder so you can write:
 *                Repository.builder().name("my-repo").status(UPLOADING).build()
 *            instead of: new Repository(); repo.setName("my-repo"); ...
 * @NoArgsConstructor — Generates a no-argument constructor: new Repository()
 *                      JPA requires this — it creates empty objects then
 *                      fills in the fields itself.
 * @AllArgsConstructor — Generates a constructor with ALL fields as parameters.
 *                       Required by @Builder when combined with @NoArgsConstructor.
 * @ToString  — Generates a human-readable toString() method, handy for logging.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString

// ── JPA annotations ───────────────────────────────────────────────────────

/**
 * @Entity — Tells Spring/Hibernate "this class maps to a database table".
 *           Without this, JPA ignores the class completely.
 * @Table(name = "repository") — Explicitly names the table "repository".
 *           If you omit this, JPA uses the class name by default (also
 *           "Repository"), but being explicit is clearer.
 */
@Entity
@Table(name = "repository")
public class Repository {

    /**
     * @Id — Marks this field as the PRIMARY KEY of the table.
     *       Every JPA entity must have exactly one @Id field.
     *
     * @GeneratedValue(strategy = GenerationType.IDENTITY)
     *       — Tells the database to auto-increment this value.
     *         Every time you save a new Repository, the DB assigns the
     *         next available integer (1, 2, 3, …). You never set it yourself.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * @Column(nullable = false) — SQL: NOT NULL. The name is required.
     *                             Hibernate will throw an error at startup
     *                             if you try to save a Repository with no name.
     */
    @Column(nullable = false)
    private String name;

    /**
     * LocalDateTime — Java's type for "a date and time without timezone".
     * We record exactly when the upload happened.
     *
     * @Column(nullable = false, updatable = false)
     *   nullable = false  → cannot be NULL in the database.
     *   updatable = false → once saved, this column is never changed by JPA.
     *                       Good for "created at" timestamps.
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    /**
     * @Enumerated(EnumType.STRING)
     *
     * ─── THE MOST IMPORTANT ENUM ANNOTATION — READ THIS ───────────────────
     *
     * Without @Enumerated: JPA saves the ORDINAL (position) of the enum.
     *   UPLOADING → 0, INDEXING → 1, READY → 2, FAILED → 3
     *   Problem: if you ever add or re-order enum values, all your existing
     *   database rows silently point to the WRONG status. Catastrophic.
     *
     * With @Enumerated(EnumType.STRING): JPA saves the NAME of the enum.
     *   UPLOADING → "UPLOADING", INDEXING → "INDEXING", etc.
     *   Safe: adding new values or reordering never breaks existing data.
     *   Readable: you can look at the DB table and understand the value.
     *
     * Always use EnumType.STRING unless you have a very specific reason not to.
     * ────────────────────────────────────────────────────────────────────────
     *
     * @Column(nullable = false) — Status must always be set.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryStatus status;

    /**
     * Automatically set uploadedAt to now before the entity is first saved.
     *
     * @PrePersist — A JPA lifecycle callback. The method annotated with this
     *              runs automatically just before Hibernate inserts a new row.
     *              This means you never have to manually write
     *              repository.setUploadedAt(LocalDateTime.now()) — it just happens.
     */
    @PrePersist
    public void prePersist() {
        this.uploadedAt = LocalDateTime.now();
        // If status wasn't explicitly set, default it to UPLOADING
        if (this.status == null) {
            this.status = RepositoryStatus.UPLOADING;
        }
    }
}
