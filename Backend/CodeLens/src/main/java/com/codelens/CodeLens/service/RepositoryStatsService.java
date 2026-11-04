package com.codelens.CodeLens.service;

import com.codelens.CodeLens.dto.RepositoryStatsDto;
import com.codelens.CodeLens.exception.RepositoryNotFoundException;
import com.codelens.CodeLens.model.CodeFile;
import com.codelens.CodeLens.model.FileType;
import com.codelens.CodeLens.model.Repository;
import com.codelens.CodeLens.repository.CodeFileRepository;
import com.codelens.CodeLens.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes statistics for an indexed repository using pure database queries
 * and Java Stream operations. No AI is involved.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  JAVA STREAMS — A BEGINNER'S GUIDE
 * ════════════════════════════════════════════════════════════════════════════
 *
 * A Stream is a pipeline for processing a sequence of elements (like a List).
 * Think of it as an assembly line: raw data goes in one end, transformed
 * data comes out the other.
 *
 * Every stream pipeline has three parts:
 *
 *   1. SOURCE        — where the data comes from
 *   2. INTERMEDIATE  — zero or more transformation steps (lazy, not run yet)
 *   3. TERMINAL      — triggers execution and produces a result
 *
 * Example assembly line:
 *
 *   list.stream()              ← SOURCE:        open a stream on the list
 *       .filter(f -> f > 5)    ← INTERMEDIATE:  keep only elements > 5
 *       .map(f -> f * 2)       ← INTERMEDIATE:  double each remaining element
 *       .collect(toList())     ← TERMINAL:      run everything, gather into List
 *
 * KEY INSIGHT: Intermediate operations are LAZY — they don't execute until
 * a terminal operation (collect, count, findFirst, etc.) is called.
 * The pipeline runs top-to-bottom exactly once.
 *
 * LAMBDA EXPRESSIONS  (the f -> f > 5 part)
 * —————————————————————————————————————————
 * A lambda is a small, inline function. The arrow -> separates:
 *   Left side:  the parameter(s)        (f)
 *   Right side: what to do with them    (f > 5)
 *
 * You can read  f -> f.getLanguage()  as:
 *   "for each element (which I'll call f), give me its language"
 *
 * METHOD REFERENCES  (the FileType::name part)
 * ─────────────────────────────────────────────
 * f -> f.getLanguage()  can be shortened to  CodeFile::getLanguage
 * when the lambda just calls one method on the parameter. Both mean the same.
 *
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryStatsService {

    private final CodeFileRepository codeFileRepository;
    private final RepositoryRepository repositoryRepository;

    /**
     * Computes a full statistics snapshot for the given repository.
     *
     * Strategy: fetch all CodeFiles once, then use streams to compute
     * every metric from that in-memory list.
     *
     * Why one query and then streams, rather than many COUNT queries?
     * ────────────────────────────────────────────────────────────────
     * Option A — Many DB queries (N+1 pattern):
     *   countByRepositoryId(id)                     → 1 query
     *   countByRepositoryIdAndFileType(id, CTRL)    → 1 query
     *   countByRepositoryIdAndFileType(id, SERVICE) → 1 query
     *   ... (one query per FileType)
     *   Total: ~8 database round-trips
     *
     * Option B — One query + in-memory streams (what we do):
     *   findByRepositoryId(id) → 1 query, returns all files
     *   Then stream over the list for every metric → 0 more queries
     *   Total: 1 database round-trip
     *
     * For a typical project (< 500 files), Option B is faster overall
     * because network round-trips to the DB are expensive.
     * For very large repos (10,000+ files) you'd switch to Option A.
     */
    public RepositoryStatsDto computeStats(Long repositoryId) {

        // Verify the repository exists — throws 404 if not
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));

        // ── One DB query to get everything we need ────────────────────────
        List<CodeFile> files = codeFileRepository.findByRepositoryId(repositoryId);
        int total = files.size();

        log.info("Computing stats for repository id={}, {} files found", repositoryId, total);

        // ── Stream pipeline 1: Language breakdown as percentages ──────────
        Map<String, Double> languageBreakdown = computeLanguageBreakdown(files, total);

        // ── Stream pipeline 2–7: Count per FileType ───────────────────────
        long controllers    = countByType(files, FileType.CONTROLLER);
        long services       = countByType(files, FileType.SERVICE);
        long repositories   = countByType(files, FileType.REPOSITORY_LAYER);
        long entities       = countByType(files, FileType.ENTITY);
        long reactComponents = countByType(files, FileType.REACT_COMPONENT);
        long other          = countByType(files, FileType.OTHER);

        return RepositoryStatsDto.builder()
                .repositoryId(repositoryId)
                .repositoryName(repo.getName())
                .totalFiles(total)
                .languageBreakdown(languageBreakdown)
                .controllers(controllers)
                .services(services)
                .repositories(repositories)
                .entities(entities)
                .reactComponents(reactComponents)
                .other(other)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Stream pipelines — each explained in detail
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Computes language percentages as a Map<String, Double>.
     *
     * ─── Step-by-step breakdown ──────────────────────────────────────────
     *
     * Given:  files = [Foo.java(java), Bar.java(java), App.ts(typescript)]
     * Total:  3
     *
     * STEP 1 — stream()
     *   Opens a stream: think of it as connecting the list to a pipe.
     *   Nothing happens yet.
     *
     * STEP 2 — filter(f -> f.getLanguage() != null)
     *   Removes elements where language is null (files with no detected language).
     *   f -> f.getLanguage() != null  means:
     *     "for each file f, keep it if its language is not null"
     *   After this step: only files with a known language flow through.
     *
     * STEP 3 — collect(Collectors.groupingBy(...))
     *   This is the most powerful step. groupingBy is like SQL's GROUP BY.
     *   It partitions the stream elements into groups based on a key function.
     *
     *   groupingBy(CodeFile::getLanguage)
     *     — Groups files by their language value.
     *       "java"       → [Foo.java, Bar.java]
     *       "typescript" → [App.ts]
     *
     *   The second argument Collectors.counting() tells it what to do
     *   with each group: count how many elements are in it.
     *       "java"       → 2
     *       "typescript" → 1
     *
     *   Result type: Map<String, Long>
     *     { "java" → 2L, "typescript" → 1L }
     *
     * STEP 4 — entryset().stream().collect(toMap(...))
     *   We now convert the count map into a percentage map.
     *
     *   entrySet() returns the map as a Set of (key, value) pairs.
     *   We stream over those pairs and for each pair:
     *     key → keep the language name as-is
     *     value → convert count to percentage and round to 1 decimal
     *
     *   Percentage formula:
     *     percentage = (count / total) * 100.0
     *     Rounded to 1dp using: Math.round(x * 10.0) / 10.0
     *       e.g. 66.666... → 66.666... * 10 = 666.66 → round = 667 → / 10 = 66.7
     *
     *   Result type: Map<String, Double>
     *     { "java" → 66.7, "typescript" → 33.3 }
     */
    private Map<String, Double> computeLanguageBreakdown(List<CodeFile> files, int total) {
        if (total == 0) return Map.of(); // Guard against division by zero

        // STEP 1 + 2 + 3: Group by language, count each group
        Map<String, Long> countByLanguage = files.stream()
                // filter: keep only files where language is not null or blank
                .filter(f -> f.getLanguage() != null && !f.getLanguage().isBlank())
                // collect with groupingBy: GROUP BY language, COUNT(*)
                .collect(Collectors.groupingBy(
                        CodeFile::getLanguage,   // the grouping key: the language string
                        Collectors.counting()    // what to produce per group: a count
                ));
        // countByLanguage is now: { "java" → 29L, "yaml" → 5L, ... }

        // STEP 4: Convert raw counts → percentage Map
        return countByLanguage.entrySet().stream()
                // entrySet() gives us pairs like: ("java", 29)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,   // key stays the same: "java"
                        entry -> {           // value is transformed to a percentage
                            double pct = (entry.getValue() * 100.0) / total;
                            // Round to 1 decimal place:
                            // 61.7021... → 61.7021 * 10 = 617.021 → round → 617 → / 10 = 61.7
                            return Math.round(pct * 10.0) / 10.0;
                        }
                ));
    }

    /**
     * Counts how many files in the list have the given FileType.
     *
     * ─── Step-by-step breakdown ──────────────────────────────────────────
     *
     * files.stream()
     *   — Opens a stream on the list.
     *
     * .filter(f -> f.getFileType() == targetType)
     *   — Keeps only files where fileType matches our target.
     *     == is safe here because FileType is an enum — each value
     *     is a singleton, so == and .equals() are equivalent.
     *
     * .count()
     *   — Terminal operation. Counts how many elements passed the filter.
     *     Returns a long (64-bit integer).
     *     Equivalent to:  SELECT COUNT(*) ... WHERE file_type = ?
     *     but operating on the in-memory list instead of the database.
     *
     * ─── Why a helper method instead of repeating the stream inline? ──────
     * DRY (Don't Repeat Yourself). Without this helper, we'd write the same
     * stream+filter+count pattern 6 times (once per FileType). Any bug fix
     * would need to be applied 6 times. With the helper, fix it once.
     */
    private long countByType(List<CodeFile> files, FileType targetType) {
        return files.stream()
                .filter(f -> f.getFileType() == targetType)
                .count();
    }
}
