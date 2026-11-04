package com.codelens.CodeLens.model;

/**
 * Represents the lifecycle state of an uploaded code repository.
 *
 * UPLOADING → The ZIP file is currently being received and extracted.
 * INDEXING  → Files have been extracted; we are now chunking them
 *             and generating AI embeddings.
 * READY     → All chunks are embedded; the repo can be queried.
 * FAILED    → Something went wrong during ingestion or embedding.
 */
public enum RepositoryStatus {
    UPLOADING,
    INDEXING,
    READY,
    FAILED
}
