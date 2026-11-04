package com.codelens.CodeLens.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Standard error response structure returned across all REST endpoints.
 *
 * Every error response looks like this:
 * {
 *   "timestamp": "2026-09-17T14:30:00",
 *   "status": 404,
 *   "message": "Repository not found with id: 42"
 * }
 *
 * Using a typed record instead of a Map<String, Object> provides:
 *  1. Type safety — compiler prevents typos in JSON field names
 *  2. Clean API contracts — frontends know exactly what fields to expect
 *  3. Zero boilerplate — Java records auto-generate getters, constructor, and serialization
 *
 * @param timestamp Time when the error occurred
 * @param status    HTTP status code (e.g. 404, 413, 500)
 * @param message   Human-readable explanation of what went wrong
 */
public record ErrorResponse(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp,
        int status,
        String message
) {
    /**
     * Convenience constructor that automatically stamps the current time.
     */
    public ErrorResponse(int status, String message) {
        this(LocalDateTime.now(), status, message);
    }
}
