package com.codelens.CodeLens.ingestion;

/**
 * Represents a single HTTP endpoint discovered inside a source file.
 *
 * ─── What is a Java Record? ────────────────────────────────────────────────
 * A `record` (introduced in Java 16) is a compact, immutable data class.
 *
 * Writing:
 *   public record EndpointInfo(String httpMethod, String path) {}
 *
 * Is equivalent to a class with:
 *   - Two final fields: httpMethod and path
 *   - A constructor: EndpointInfo(String httpMethod, String path)
 *   - Accessors: httpMethod() and path()   ← note: no "get" prefix
 *   - Auto-generated toString(), equals(), hashCode()
 *
 * Records are perfect for small, read-only data carriers like this one.
 * We use them for EndpointInfo because an endpoint is a fact — once
 * extracted it never changes.
 *
 * Example values:
 *   httpMethod = "GET",  path = "/api/employees"
 *   httpMethod = "POST", path = "/api/employees/{id}"
 * ───────────────────────────────────────────────────────────────────────────
 */
public record EndpointInfo(

        /**
         * The HTTP verb: "GET", "POST", "PUT", "DELETE", "PATCH".
         * Derived from the annotation name: @GetMapping → "GET", etc.
         */
        String httpMethod,

        /**
         * The URL path from the mapping annotation.
         * E.g. "/api/employees", "/api/employees/{id}"
         */
        String path
) {}
