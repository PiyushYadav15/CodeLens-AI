package com.codelens.CodeLens.controller;

import com.codelens.CodeLens.dto.ChatRequestDto;
import com.codelens.CodeLens.dto.ChatResponseDto;
import com.codelens.CodeLens.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing the RAG-powered codebase question answering endpoint.
 *
 * Endpoint:
 *   POST /api/repositories/{id}/chat
 *
 * Request Body:
 *   {
 *     "question": "Where are user login credentials validated?"
 *   }
 *
 * Response Body:
 *   {
 *     "answer": "Login credentials are validated in AuthService.java ...",
 *     "sources": [
 *       {
 *         "filePath": "src/main/java/com/example/AuthService.java",
 *         "startLine": 25,
 *         "endLine": 48
 *       }
 *     ]
 *   }
 */
@Slf4j
@RestController
@RequestMapping("/api/repositories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final RagService ragService;

    /**
     * Answers a question about the repository identified by {id}.
     *
     * @param id      The database ID of the repository
     * @param request The JSON payload containing the user's question
     * @return 200 OK with ChatResponseDto containing answer and source references,
     *         or 400 Bad Request if the question is missing or blank
     */
    @PostMapping("/{id}/chat")
    public ResponseEntity<ChatResponseDto> chat(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ChatRequestDto request) {

        if (request == null || request.question() == null || request.question().isBlank()) {
            log.warn("Received empty chat question for repository id={}", id);
            return ResponseEntity.badRequest().body(
                    new ChatResponseDto("Question must not be empty.", java.util.Collections.emptyList())
            );
        }

        ChatResponseDto response = ragService.chat(id, request.question().trim());
        return ResponseEntity.ok(response);
    }
}
