package com.codelens.CodeLens.dto;

/**
 * Represents a specific source citation pointing back to the original codebase.
 *
 * Example JSON:
 * {
 *   "filePath": "src/main/java/com/example/service/AuthService.java",
 *   "startLine": 24,
 *   "endLine": 48
 * }
 *
 * This allows frontends to show clickable links or code highlights so
 * developers can verify the AI's answers directly against the source files.
 *
 * @param filePath  Relative path of the source file inside the repository
 * @param startLine First line of the referenced code snippet (1-based)
 * @param endLine   Last line of the referenced code snippet (inclusive)
 */
public record SourceRef(String filePath, int startLine, int endLine) {
}
