package com.codelens.CodeLens.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when something goes wrong during ZIP extraction.
 * E.g. the uploaded file is not a valid ZIP, or is corrupted,
 * or exceeds the size limit.
 *
 * HTTP 422 Unprocessable Entity — the server understood the request
 * (it received a file), but could not process its content (the ZIP is bad).
 * This is more accurate than 400 Bad Request (which means malformed HTTP)
 * or 500 Internal Server Error (which implies a bug in our code).
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ZipExtractionException extends RuntimeException {

    public ZipExtractionException(String message) {
        super(message);
    }

    /**
     * This constructor wraps a lower-level exception (the "cause").
     * For example, if ZipInputStream throws an IOException, we catch it
     * and wrap it: new ZipExtractionException("...", ioException)
     *
     * This preserves the original stack trace so you can debug what
     * actually went wrong inside the ZIP parsing code.
     */
    public ZipExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
