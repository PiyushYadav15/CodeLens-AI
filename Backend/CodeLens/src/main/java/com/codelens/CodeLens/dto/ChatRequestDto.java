package com.codelens.CodeLens.dto;

/**
 * Incoming request payload when a user asks a question about a repository.
 *
 * Example JSON:
 * {
 *   "question": "How does user authentication work in this project?"
 * }
 *
 * Using a Java record gives us immutable fields, automatic constructor,
 * getters, equals(), hashCode(), and toString() with zero boilerplate.
 *
 * @param question The natural language question asked by the user
 */
public record ChatRequestDto(String question) {
}
