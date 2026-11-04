package com.codelens.CodeLens.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a requested Repository ID does not exist in the database.
 *
 * @ResponseStatus(HttpStatus.NOT_FOUND)
 *   — When this exception bubbles up to Spring's DispatcherServlet,
 *     Spring automatically responds with HTTP 404 Not Found.
 *     Without this, Spring would return HTTP 500 Internal Server Error,
 *     which would be misleading — the server didn't crash, the resource
 *     just doesn't exist.
 *
 * extends RuntimeException
 *   — RuntimeException (also called "unchecked exception") means callers
 *     do NOT have to declare "throws RepositoryNotFoundException" in their
 *     method signatures. This keeps service code clean.
 *     If you extend Exception instead (checked), every method in the call
 *     chain would need "throws Exception" — very noisy.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RepositoryNotFoundException extends RuntimeException {

    /**
     * Constructor takes the missing ID and builds a clear message like:
     *   "Repository not found with id: 42"
     *
     * super(message) — passes the message up to RuntimeException, which
     * stores it. You retrieve it later via exception.getMessage().
     */
    public RepositoryNotFoundException(Long id) {
        super("Repository not found with id: " + id);
    }

    public RepositoryNotFoundException(String message) {
        super(message);
    }
}
