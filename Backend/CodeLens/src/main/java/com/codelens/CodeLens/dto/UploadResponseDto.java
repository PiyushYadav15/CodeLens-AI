package com.codelens.CodeLens.dto;

import com.codelens.CodeLens.model.RepositoryStatus;
import lombok.Builder;
import lombok.Getter;

/**
 * The JSON response body returned after a successful ZIP upload.
 *
 * Example JSON the client receives:
 * {
 *   "repositoryId": 7,
 *   "name": "my-spring-project",
 *   "status": "READY",
 *   "filesExtracted": 43,
 *   "message": "Upload successful. 43 files indexed."
 * }
 *
 * Why a DTO instead of returning the Repository entity directly?
 * ──────────────────────────────────────────────────────────────
 * 1. Control: we choose exactly which fields the client sees.
 *    (We don't want to accidentally expose internal fields added later.)
 * 2. Shape: the entity has `uploadedAt` (a LocalDateTime) which serializes
 *    in an unfriendly format by default. The DTO lets us format it cleanly.
 * 3. Decoupling: the API contract (what JSON looks like) is independent
 *    from the database schema (entity fields). They can evolve separately.
 *
 * @Getter  — Lombok generates getters. Jackson (the JSON library) needs
 *            getters to serialize an object to JSON.
 * @Builder — Lets us construct this object with a fluent builder.
 */
@Getter
@Builder
public class UploadResponseDto {

    /** The auto-generated database ID of the newly created Repository. */
    private Long repositoryId;

    /** The name of the repository (derived from the ZIP filename). */
    private String name;

    /** The final processing status (should be READY on success). */
    private RepositoryStatus status;

    /** How many source files were extracted and saved from the ZIP. */
    private int filesExtracted;

    /** A human-friendly summary message. */
    private String message;
}
