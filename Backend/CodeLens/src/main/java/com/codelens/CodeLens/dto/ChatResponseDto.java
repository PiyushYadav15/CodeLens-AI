package com.codelens.CodeLens.dto;

import java.util.List;

/**
 * Outgoing response payload returned to the user after RAG generation.
 *
 * Example JSON:
 * {
 *   "answer": "Authentication is handled in AuthService.java using JWT tokens...",
 *   "sources": [
 *     {
 *       "filePath": "src/main/java/com/example/service/AuthService.java",
 *       "startLine": 24,
 *       "endLine": 48
 *     }
 *   ]
 * }
 *
 * @param answer  The generated natural-language answer from the AI model
 * @param sources List of exact code chunks cited as factual evidence
 */
public record ChatResponseDto(String answer, List<SourceRef> sources) {
}
