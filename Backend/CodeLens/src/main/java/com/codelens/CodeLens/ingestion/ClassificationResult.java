package com.codelens.CodeLens.ingestion;

import com.codelens.CodeLens.model.FileType;
import java.util.List;

/**
 * The result of classifying a single source file.
 *
 * Bundles together:
 *  1. The FileType (CONTROLLER, SERVICE, etc.)
 *  2. Any HTTP endpoints found (only populated when fileType == CONTROLLER)
 *
 * Also a Java record — see EndpointInfo.java for a full explanation of records.
 *
 * Usage:
 *   ClassificationResult result = fileClassifierService.classify(path, content);
 *   result.fileType()    → FileType.CONTROLLER
 *   result.endpoints()   → [EndpointInfo("GET", "/api/users"), ...]
 */
public record ClassificationResult(
        FileType fileType,
        List<EndpointInfo> endpoints
) {
    /**
     * Convenience factory method for non-controller files (no endpoints).
     * Instead of writing: new ClassificationResult(FileType.SERVICE, List.of())
     * you can write:       ClassificationResult.of(FileType.SERVICE)
     */
    public static ClassificationResult of(FileType type) {
        return new ClassificationResult(type, List.of());
    }
}
