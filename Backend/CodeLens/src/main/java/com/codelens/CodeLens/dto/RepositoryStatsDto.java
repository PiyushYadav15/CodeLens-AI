package com.codelens.CodeLens.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * The JSON response body for GET /api/repositories/{id}/stats
 *
 * Example response:
 * {
 *   "repositoryId": 3,
 *   "repositoryName": "my-spring-app",
 *   "totalFiles": 47,
 *   "languageBreakdown": {
 *     "java": 78.7,
 *     "yaml": 10.6,
 *     "xml": 6.4,
 *     "properties": 4.3
 *   },
 *   "controllers": 5,
 *   "services": 8,
 *   "repositories": 6,
 *   "entities": 9,
 *   "reactComponents": 0,
 *   "other": 19
 * }
 *
 * ─── Why Map<String, Double> for languageBreakdown? ────────────────────────
 * We don't know in advance what languages will be in the ZIP — the user
 * could upload a Python project, a Node.js project, or a mixed one.
 * A fixed-field DTO like { javaPercent, pythonPercent, ... } would need a
 * field for every possible language. A Map is open-ended — any language
 * name becomes a key automatically. Jackson serialises a Map<String, Double>
 * directly into a JSON object: { "java": 78.7, "python": 12.3 }
 * ───────────────────────────────────────────────────────────────────────────
 */
@Getter
@Builder
public class RepositoryStatsDto {

    /** The repository's database ID */
    private Long repositoryId;

    /** The repository's name (e.g. "my-spring-app") */
    private String repositoryName;

    /** Total number of source files extracted from the ZIP */
    private int totalFiles;

    /**
     * Language distribution as percentages rounded to 1 decimal place.
     * Key   = language name (e.g. "java", "typescript")
     * Value = percentage of total files (e.g. 78.7 means 78.7%)
     */
    private Map<String, Double> languageBreakdown;

    /** Count of files classified as CONTROLLER */
    private long controllers;

    /** Count of files classified as SERVICE */
    private long services;

    /** Count of files classified as REPOSITORY_LAYER */
    private long repositories;

    /** Count of files classified as ENTITY */
    private long entities;

    /** Count of files classified as REACT_COMPONENT */
    private long reactComponents;

    /** Count of files classified as OTHER (config, tests, build scripts, etc.) */
    private long other;
}
