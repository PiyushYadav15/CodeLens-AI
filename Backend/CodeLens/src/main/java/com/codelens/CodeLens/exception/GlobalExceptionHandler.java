package com.codelens.CodeLens.exception;

import com.codelens.CodeLens.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized exception handler for all controllers across the application.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHAT DOES @RestControllerAdvice DO DIFFERENTLY FROM NORMAL try/catch?
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 1. SEPARATION OF CONCERNS (Clean Controllers):
 *    Without @RestControllerAdvice, every controller method would be cluttered:
 *      try {
 *          return ResponseEntity.ok(repositoryService.getById(id));
 *      } catch (RepositoryNotFoundException e) {
 *          return ResponseEntity.status(404).body(new ErrorResponse(...));
 *      } catch (Exception e) {
 *          return ResponseEntity.status(500).body(...);
 *      }
 *    With @RestControllerAdvice, controllers focus ONLY on the "happy path":
 *      return ResponseEntity.ok(repositoryService.getById(id));
 *    If an exception happens, Spring automatically intercepts it and delegates
 *    to this class.
 *
 * 2. CONSISTENT ERROR CONTRACT:
 *    Every client (React, curl, Postman, mobile app) receives identical JSON:
 *    {
 *      "timestamp": "2026-09-17T14:35:10",
 *      "status": 404,
 *      "message": "Repository not found with id: 42"
 *    }
 *    Different developers won't invent their own error JSON shapes across endpoints.
 *
 * 3. SPRING FILTERS AND AUTOMATIC ERRORS:
 *    Some errors occur BEFORE your controller method even executes!
 *    For example: MaxUploadSizeExceededException is thrown by Spring's file
 *    upload filter when the request headers/body arrive. A try/catch inside your
 *    controller would NEVER catch it because the controller method was never entered.
 *    @RestControllerAdvice intercepts exceptions across the entire Web MVC dispatch.
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles: RepositoryNotFoundException (e.g. GET /api/repositories/999)
     * Returns: HTTP 404 Not Found
     */
    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRepositoryNotFound(RepositoryNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handles: File upload exceeding spring.servlet.multipart.max-file-size (e.g. > 50MB)
     * Thrown by Spring's multipart resolver before the controller method runs.
     * Returns: HTTP 413 Payload Too Large
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("File upload exceeded maximum allowed limit: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Uploaded file exceeds the maximum allowed upload limit (50 MB)."
        );

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    /**
     * Handles: Corrupt or invalid ZIP archive extraction errors
     * Returns: HTTP 422 Unprocessable Entity
     */
    @ExceptionHandler(ZipExtractionException.class)
    public ResponseEntity<ErrorResponse> handleZipExtraction(ZipExtractionException ex) {
        log.error("ZIP extraction failed: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Handles: Requests for static resources that don't exist (e.g. GET /, GET /favicon.ico)
     *
     * Spring throws NoResourceFoundException (a subclass of Exception) when the
     * DispatcherServlet cannot find a static file for a request. Without this handler
     * it would fall through to the catch-all below and incorrectly return 500.
     * Returns: HTTP 404 Not Found
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        // Log at DEBUG — this is normal browser behaviour (favicon, root path, etc.)
        log.debug("No static resource found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "The requested resource was not found."
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handles: Catch-all fallback for any unanticipated runtime or checked exceptions
     * Returns: HTTP 500 Internal Server Error
     *
     * Note: We log the full stack trace server-side with log.error(..., ex),
     * but return a safe, sanitized message to the client to avoid leaking internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("Unhandled exception caught in GlobalExceptionHandler: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected server error occurred. Please check server logs or try again."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
