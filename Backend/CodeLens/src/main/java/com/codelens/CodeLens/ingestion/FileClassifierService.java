package com.codelens.CodeLens.ingestion;

import com.codelens.CodeLens.model.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a source file's architectural role by inspecting its
 * content for well-known Spring/React annotations.
 *
 * ─── Design Principle: Content > Path ──────────────────────────────────────
 * The old approach (in ZipExtractorService) guessed type from the FILE PATH.
 * That's fragile — what if someone names their controller "UserHandler.java"?
 * Looking at the actual ANNOTATIONS in the file content is far more reliable.
 *
 * Priority order (highest to lowest):
 *   1. Content-based annotation check   ← most reliable
 *   2. File extension check (React)     ← extension is definitive for .jsx/.tsx
 *   3. Path-based heuristic             ← fallback for files without annotations
 *   4. OTHER                            ← default
 * ───────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class FileClassifierService {

    // ═══════════════════════════════════════════════════════════════════════
    // REGEX PATTERNS — Compiled once, reused for every file
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ─── What is Pattern.compile()? ────────────────────────────────────────
     *
     * A regex pattern must be "compiled" (parsed into an internal structure)
     * before it can be used. Compilation is expensive — doing it inside a loop
     * would recompile the same pattern thousands of times.
     *
     * By storing compiled patterns as static final fields, we compile each
     * pattern ONCE when the class is loaded, then reuse the compiled object
     * for every file. Much faster.
     *
     * Pattern.MULTILINE
     *   — Changes the meaning of ^ and $.
     *     Without it: ^ = start of entire string, $ = end of entire string.
     *     With it:    ^ = start of any line,      $ = end of any line.
     *     We need this because annotations appear on their own line in source code.
     * ───────────────────────────────────────────────────────────────────────
     */

    /**
     * ENDPOINT EXTRACTION PATTERN
     *
     * Matches Spring MVC mapping annotations like:
     *   @GetMapping("/api/users")
     *   @PostMapping("/api/users/{id}")
     *   @DeleteMapping(value = "/api/users/{id}")
     *   @PutMapping(  "/api/items"  )          ← extra whitespace is fine
     *   @RequestMapping(method=RequestMethod.GET, value="/api/v1/users")
     *
     * Let's break the pattern apart piece by piece:
     *
     *   @(Get|Post|Put|Delete|Patch)Mapping
     *   ─────────────────────────────────────
     *   @        → literal "@" character
     *   (        → start of a CAPTURING GROUP (group 1)
     *              A capturing group saves the matched text so we can read it.
     *   Get|Post|Put|Delete|Patch
     *            → alternation (like "or"): match any ONE of these words
     *   )        → end of group 1 → captured text = "Get", "Post", etc.
     *   Mapping  → literal text "Mapping"
     *
     *   \s*      → zero or more whitespace characters (\s = space, tab, newline)
     *              Handles: @GetMapping("/path") and @GetMapping  ("/path")
     *
     *   \(       → literal "(" — must be escaped because ( has special regex meaning
     *
     *   \s*      → optional whitespace inside the parenthesis
     *
     *   (?:value\s*=\s*)?
     *   ─────────────────
     *   (?:      → NON-CAPTURING group — groups without saving the matched text
     *              We use this when we just need grouping, not capturing.
     *   value    → literal "value" keyword (as in: @GetMapping(value = "/path"))
     *   \s*=\s*  → equals sign with optional whitespace on both sides
     *   )        → end of non-capturing group
     *   ?        → the whole group is OPTIONAL (matches 0 or 1 times)
     *              Handles both @GetMapping("/path") and @GetMapping(value="/path")
     *
     *   "([^"]*)"
     *   ─────────
     *   "        → literal opening double-quote
     *   (        → start of capturing group 2 — this is the path we want
     *   [^"]*    → character class: [^"] means "any character EXCEPT double-quote"
     *              *  means zero or more times
     *              Together: match everything up to (but not including) the closing "
     *   )        → end of group 2
     *   "        → literal closing double-quote
     *
     *   Full example match:
     *     @PostMapping("/api/employees/{id}")
     *     Group 1: "Post"
     *     Group 2: "/api/employees/{id}"
     */
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\""
    );

    /**
     * REACT COMPONENT PATTERN
     *
     * Matches React functional component declarations:
     *   function UserCard(    ← capital U
     *   function AdminPanel(
     *   export function MyButton(
     *
     * Pattern breakdown:
     *
     *   (?:export\s+)?
     *   ─────────────
     *   (?:      → non-capturing group (we don't need to capture "export")
     *   export   → literal "export" keyword
     *   \s+      → one or more whitespace characters (space between export and function)
     *   )        → end of non-capturing group
     *   ?        → the export keyword is OPTIONAL
     *
     *   function\s+
     *   ───────────
     *   function → literal keyword
     *   \s+      → one or more spaces between "function" and the name
     *
     *   ([A-Z][a-zA-Z0-9]*)
     *   ────────────────────
     *   (        → capturing group 1 — the component name
     *   [A-Z]    → character class: exactly ONE uppercase letter (A through Z)
     *              React convention: component names MUST start with a capital letter.
     *              This is how React distinguishes <MyComponent/> from <div/>
     *   [a-zA-Z0-9]*
     *            → zero or more letters (upper or lower) or digits
     *              * means zero or more — the name can be just one capital letter
     *   )        → end of group 1
     *
     *   \s*\(
     *   ──────
     *   \s*      → optional whitespace before the opening paren
     *   \(       → literal "(" — the function's parameter list opens here
     *
     *   Example matches:
     *     "function UserCard("        → group 1 = "UserCard"
     *     "export function MyBtn("    → group 1 = "MyBtn"
     *     "function lowercase(" → NO MATCH (lowercase start = not a component)
     */
    private static final Pattern REACT_COMPONENT_PATTERN = Pattern.compile(
            "(?:export\\s+)?function\\s+([A-Z][a-zA-Z0-9]*)\\s*\\(",
            Pattern.MULTILINE
    );

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Classifies a file and extracts any HTTP endpoints if it's a controller.
     *
     * @param path    The normalised file path (e.g. "src/controller/UserCtrl.java")
     * @param content The full text content of the file
     * @return        A ClassificationResult with fileType and (possibly) endpoints
     */
    public ClassificationResult classify(String path, String content) {
        FileType type = determineFileType(path, content);

        // Only extract endpoints if this is a controller file —
        // no point running regex on service or entity files.
        if (type == FileType.CONTROLLER) {
            List<EndpointInfo> endpoints = extractEndpoints(content);
            log.debug("Classified '{}' as CONTROLLER with {} endpoint(s)", path, endpoints.size());
            return new ClassificationResult(type, endpoints);
        }

        log.debug("Classified '{}' as {}", path, type);
        return ClassificationResult.of(type);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Classification Logic
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Determines FileType using a priority-ordered set of checks.
     *
     * Why check CONTENT first, then PATH?
     *   Content is ground truth — the annotations in the file are definitive.
     *   Path is a heuristic — a file could be named anything.
     *   Content check wins because it's more reliable.
     */
    private FileType determineFileType(String path, String content) {

        // ── 1. Content-based checks (most reliable) ─────────────────────────

        // contains() is a simple substring search — O(n) but fine for files.
        // We check for the annotation text exactly as it appears in source code.

        if (content.contains("@RestController") || content.contains("@Controller")) {
            return FileType.CONTROLLER;
        }
        // Check @Service AFTER controller because some classes might have
        // both @RestController and other annotations — controller takes priority.
        if (content.contains("@Service")) {
            return FileType.SERVICE;
        }
        if (content.contains("@Repository")) {
            // NOTE: This detects Spring's @Repository annotation in the FILE CONTENT.
            // It does NOT conflict with our model.Repository class — we're just
            // searching for the text "@Repository" inside another file's source code.
            return FileType.REPOSITORY_LAYER;
        }
        if (content.contains("@Entity")) {
            return FileType.ENTITY;
        }

        // ── 2. Extension-based check for React components ───────────────────
        String lower = path.toLowerCase();
        if (lower.endsWith(".jsx") || lower.endsWith(".tsx")) {
            // Even if it's a .jsx/.tsx file, confirm it actually declares
            // a component (function with capital letter) — not just any JS file
            // with a React extension.
            if (REACT_COMPONENT_PATTERN.matcher(content).find()) {
                return FileType.REACT_COMPONENT;
            }
            // It's a .jsx/.tsx but has no component function — still treat as OTHER
            // (could be a utility file, constants file, etc.)
        }

        // ── 3. Path-based fallback heuristics ───────────────────────────────
        // Use path clues only when content gives us nothing definitive.
        // This handles files that have the right name/location but no annotations
        // (e.g. interface-based repositories, abstract service classes).
        if (lower.contains("/controller/") || lower.endsWith("controller.java")) {
            return FileType.CONTROLLER;
        }
        if (lower.contains("/service/") || lower.endsWith("service.java")
                || lower.endsWith("serviceimpl.java")) {
            return FileType.SERVICE;
        }
        if (lower.contains("/repository/") || lower.contains("/repo/")
                || lower.endsWith("repository.java") || lower.endsWith("repo.java")) {
            return FileType.REPOSITORY_LAYER;
        }
        if (lower.contains("/entity/") || lower.contains("/model/")
                || lower.contains("/domain/") || lower.endsWith("entity.java")) {
            return FileType.ENTITY;
        }

        // ── 4. Default ───────────────────────────────────────────────────────
        return FileType.OTHER;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Endpoint Extraction
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Scans the file content for Spring MVC mapping annotations and returns
     * all discovered HTTP endpoints.
     *
     * ─── How Matcher works ─────────────────────────────────────────────────
     *
     * Pattern  = the compiled regex (a "template" to search for)
     * Matcher  = the engine that applies the pattern to a specific input string
     *
     * Think of Pattern as a stamp, and Matcher as pressing that stamp
     * into a specific piece of text. You can press it repeatedly to find
     * all occurrences.
     *
     * matcher.find()
     *   — Searches forward in the input string for the NEXT match.
     *     Returns true if a match was found, false when there are no more.
     *     Calling it in a while loop finds ALL matches.
     *
     * matcher.group(1), matcher.group(2)
     *   — Returns the text captured by group 1 or group 2.
     *     Group numbers correspond to the order of ( in the pattern.
     *     Group 0 is always the entire match.
     *
     * Example — given this content:
     *   @GetMapping("/api/users")
     *   @PostMapping("/api/users/{id}")
     *
     * First find():  group(1)="Get",  group(2)="/api/users"
     * Second find(): group(1)="Post", group(2)="/api/users/{id}"
     * Third find():  returns false → loop ends
     * ───────────────────────────────────────────────────────────────────────
     *
     * @param content The full source code of a controller file
     * @return List of discovered endpoints, empty if none found
     */
    public List<EndpointInfo> extractEndpoints(String content) {
        List<EndpointInfo> endpoints = new ArrayList<>();

        // Create a Matcher for this specific content string
        Matcher matcher = ENDPOINT_PATTERN.matcher(content);

        // Keep calling find() until no more matches remain
        while (matcher.find()) {
            String verb = matcher.group(1).toUpperCase(); // "Get" → "GET"
            String path = matcher.group(2);               // "/api/users"

            endpoints.add(new EndpointInfo(verb, path));
            log.debug("  Found endpoint: {} {}", verb, path);
        }

        return endpoints;
    }
}
