package com.codelens.CodeLens.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits source file content into semantically meaningful chunks.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHY METHOD-LEVEL CHUNKING IS BETTER THAN LINE-BASED CHUNKING
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The naive approach is to split every 60 lines with 10-line overlap.
 * That's simple but has a critical flaw:
 *
 *   public String calculateTotal(Order order) {   ← line 58
 *       // ... (cut here at line 60!)
 *       return total.toString();                  ← line 61 (in next chunk)
 *   }
 *
 * When the user asks "how is the total calculated?", the AI gets chunk 1
 * with the method START but no return, and chunk 2 with the return but
 * no context. Neither chunk is useful alone.
 *
 * Method-level chunking keeps each method INTACT:
 *   Chunk 1: calculateTotal() — complete, start to end
 *   Chunk 2: processPayment() — complete, start to end
 *
 * The AI can fully understand each chunk independently. Retrieval quality
 * improves dramatically.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  BRACE COUNTING — HOW IT WORKS (with a tiny example first)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Problem: once we find a method signature, how do we know where it ends?
 * A method ends at its MATCHING closing brace. But the method might contain
 * nested if/for/try blocks that also have braces. We can't just find the
 * first '}' — that might close an inner if-block.
 *
 * Solution: count braces. Each '{' increments a counter. Each '}' decrements
 * it. When the counter returns to 0, we've found the matching closing brace.
 *
 * ─── Example on a tiny 3-line method: ───────────────────────────────────
 *
 *   public int add(int a, int b) {    ← we find this signature with regex
 *       if (a > 0) { return a + b; }  ← inner braces
 *       return b;
 *   }
 *
 * We start scanning from the OPENING '{' of the method (end of line 1):
 *
 *   Character   Counter   Event
 *   ─────────────────────────────────────────────────────
 *   {           1         ← method opens (counter goes 0 → 1)
 *   {           2         ← if-block opens
 *   }           1         ← if-block closes  (NOT the method end, counter > 0)
 *   }           0         ← METHOD ENDS HERE  (counter reaches 0 ✓)
 *
 * As soon as the counter hits 0, we stop. The text from the method
 * signature to this final '}' is our chunk.
 *
 * Key insight: we count { as +1 and } as -1. When the running total
 * returns to 0 for the first time, we've found the end of the method.
 *
 * This works correctly no matter how deeply nested the inner code is:
 *   try { for(...) { if(...) { } } }  — counter goes 1→2→3→2→1→0
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class ChunkingService {

    // ── Constants ─────────────────────────────────────────────────────────

    /** Maximum lines per generic (non-Java, non-Markdown) chunk */
    private static final int GENERIC_CHUNK_SIZE = 150;

    /**
     * Regex that identifies the START of a Java method.
     *
     * We match lines that look like method signatures, i.e. lines containing:
     *   1. An access modifier or return type keyword
     *   2. A method name followed by '('
     *   3. A '{' somewhere on the same line (the opening brace of the body)
     *
     * ─── Pattern breakdown ────────────────────────────────────────────────
     *
     * ^                 → start of a line (Pattern.MULTILINE makes ^ match
     *                     each line's start, not just the whole string's start)
     *
     * \s*               → optional leading whitespace (indentation)
     *
     * (public|private|protected|static|final|abstract|default|synchronized|native|void)
     *   → The method must start with at least one of these keywords.
     *     The | means "or" — match any one of them.
     *     This is the key filter: it prevents matching random lines like
     *     "String name = getValue();" which also have parentheses but are
     *     NOT method declarations.
     *
     * [\w\s<>\[\],?@]+  → The return type + any annotations between modifier
     *                     and the method name. Very permissive — matches things
     *                     like "public static <T extends Comparable<T>> List<T>"
     *   \w = word char (letter, digit, _)
     *   \s = whitespace
     *   <>\[\],?@ = generic type brackets, array brackets, commas, etc.
     *   + = one or more of those characters
     *
     * \w+               → The METHOD NAME: one or more word characters.
     *                     (Captured separately — see the next line for why.)
     *
     * \s*\(             → optional space, then the '(' that opens parameters
     *
     * [^)]*             → the parameter list: anything that isn't a ')'
     *                     [^)] = "not a closing paren"
     *                     * = zero or more times (handles zero-parameter methods)
     *
     * \)                → the closing ')' of the parameter list
     *
     * [^{;]*            → anything between ')' and '{':
     *                     throws declarations, whitespace, etc.
     *                     [^{;] = "not a '{' and not a ';'"
     *                     * = zero or more
     *                     The ';' exclusion prevents matching abstract method
     *                     declarations which end in ';' (no body/brace)
     *
     * \{                → the opening '{' of the method body — REQUIRED.
     *                     Without this, we'd match abstract method signatures
     *                     which have no body and no brace.
     * ─────────────────────────────────────────────────────────────────────
     */
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(public|private|protected|static|final|abstract|default|synchronized|native|void)"
                    + "[\\w\\s<>\\[\\],?@]+"  // return type, generics, annotations
                    + "(\\w+)"                 // method name (captured in group 2)
                    + "\\s*\\([^)]*\\)"        // parameter list ( ... )
                    + "[^{;]*\\{",             // optional throws + opening brace
            Pattern.MULTILINE
    );

    // ═══════════════════════════════════════════════════════════════════════
    // Public API — dispatch by file extension
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Main entry point. Chooses the right chunking strategy based on the
     * file's extension and returns a list of chunks.
     *
     * @param path    Normalised file path — used to detect the extension
     * @param content Full text content of the file
     * @return        List of chunks (always at least one)
     */
    public List<Chunk> chunk(String path, String content) {
        if (content == null || content.isBlank()) {
            return List.of(Chunk.of("", 1, 1));
        }

        String lower = path.toLowerCase();

        if (lower.endsWith(".java") || lower.endsWith(".kt")) {
            return chunkJavaFile(content);
        }
        if (lower.endsWith(".md")) {
            return chunkMarkdownFile(content);
        }
        // Everything else: JS, TS, JSX, TSX, HTML, CSS, JSON, YAML, XML, SQL, properties
        return chunkGenericFile(content);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 1 — Java: method-level chunking with brace counting
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Splits a Java (or Kotlin) file into one chunk per method.
     * Uses brace-counting to find each method's exact end.
     *
     * If no methods are found (interface, annotation type, config record, etc.)
     * the entire file is returned as a single chunk — the fallback.
     *
     * @param content The full text of the .java file
     * @return        One Chunk per method, preserving start/end line numbers
     */
    public List<Chunk> chunkJavaFile(String content) {
        // Split the entire file into individual lines.
        // We need line numbers for citations ("UserService.java lines 45–82").
        // -1 in split() preserves trailing empty lines.
        String[] lines = content.split("\n", -1);

        List<Chunk> chunks = new ArrayList<>();
        Matcher matcher = METHOD_PATTERN.matcher(content);

        // Track the character offset of the start of each line.
        // We need this to convert a regex match position (character index)
        // back into a line number.
        //
        // lineStartOffsets[i] = the character offset in `content` where line i+1 begins.
        // Example for content "abc\ndef\nghi":
        //   lineStartOffsets[0] = 0   (line 1 starts at char 0)
        //   lineStartOffsets[1] = 4   (line 2 starts at char 4, after "abc\n")
        //   lineStartOffsets[2] = 8   (line 3 starts at char 8, after "abc\ndef\n")
        int[] lineStartOffsets = buildLineStartOffsets(content, lines.length);

        while (matcher.find()) {
            // matcher.start() = character offset of the first char of this match
            // matcher.end()   = character offset AFTER the last char of this match
            //                   (which is the '{' that opens the method body)

            int methodStart = matcher.start(); // char offset of "public int add("
            int braceStart  = matcher.end() - 1; // char offset of the opening '{'

            // Convert character offsets → 1-indexed line numbers
            int startLine = charOffsetToLineNumber(methodStart, lineStartOffsets);

            // ── Brace counting ───────────────────────────────────────────────
            // Scan forward from the opening '{', counting braces.
            // When the counter returns to 0, we've found the matching '}'.
            int braceCount = 0;
            int methodEndChar = braceStart; // will be updated as we scan

            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        // This '}' is the one that closes the method
                        methodEndChar = i;
                        break; // Stop scanning — we found the end
                    }
                }
            }

            if (braceCount != 0) {
                // Brace mismatch — malformed Java file (shouldn't happen with valid code).
                // Skip this method and continue to the next match.
                log.warn("Brace mismatch scanning method at offset {}; skipping.", methodStart);
                continue;
            }

            // Extract the method text: from the signature line to the closing '}'
            // +1 on methodEndChar to include the '}' itself
            String methodContent = content.substring(methodStart, methodEndChar + 1);
            int endLine = charOffsetToLineNumber(methodEndChar, lineStartOffsets);

            // Extract the method name from capture group 2
            // (Group 1 = the access modifier keyword, Group 2 = the method name)
            String methodName = matcher.group(2);

            chunks.add(new Chunk(methodContent, startLine, endLine, methodName));

            log.debug("Java chunk: method '{}' lines {}–{}", methodName, startLine, endLine);
        }

        // ── Fallback: no methods found ───────────────────────────────────
        // Happens for: interfaces, enums, annotation types, records, config classes.
        // Return the whole file as one chunk.
        if (chunks.isEmpty()) {
            log.debug("No methods found — using whole-file fallback");
            chunks.add(Chunk.of(content, 1, lines.length));
        }

        return chunks;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 2 — Markdown: split on "## " section headers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Splits a Markdown file at every "## " (H2) header.
     * Each section from one H2 to the next becomes one chunk.
     *
     * Why H2 and not H1 (#)?
     *   README files typically have one H1 (the project name) at the top
     *   and use H2 for actual sections (Installation, Usage, API, etc.).
     *   Splitting on H2 gives us meaningful, self-contained sections.
     *
     * Example:
     *   # My Project                  ← H1: stays as part of the first chunk
     *   Introduction text...
     *   ## Installation               ← H2: starts chunk 2
     *   Install with npm...
     *   ## Usage                      ← H2: starts chunk 3
     *   Usage examples...
     *
     * Result:
     *   Chunk 1: "# My Project\nIntroduction text..."  (lines 1–2)
     *   Chunk 2: "## Installation\nInstall with npm..."  (lines 3–4)
     *   Chunk 3: "## Usage\nUsage examples..."  (lines 5–6)
     *
     * @param content The full text of the .md file
     * @return        One chunk per section (or one chunk for the whole file
     *                if there are no "## " headers)
     */
    public List<Chunk> chunkMarkdownFile(String content) {
        String[] lines = content.split("\n", -1);
        List<Chunk> chunks = new ArrayList<>();

        int sectionStart = 0; // line index (0-based) where current section begins

        for (int i = 1; i < lines.length; i++) {
            // startsWith("## ") — the space after ## is important!
            // "##NoSpace" is not a valid H2 header in Markdown.
            // We also accept just "##" on its own (empty header title).
            if (lines[i].startsWith("## ") || lines[i].equals("##")) {
                // We've found the start of a NEW section at line i.
                // Everything from sectionStart to i-1 is the PREVIOUS section.
                String sectionContent = joinLines(lines, sectionStart, i - 1);
                if (!sectionContent.isBlank()) {
                    // +1 converts from 0-based index to 1-based line number
                    chunks.add(Chunk.of(sectionContent, sectionStart + 1, i));
                }
                sectionStart = i; // The new section starts here
            }
        }

        // Don't forget to add the LAST section (from sectionStart to the end)
        // The loop above only emits a chunk when it sees the START of the NEXT
        // section. The final section has nothing after it, so we must handle it
        // explicitly outside the loop.
        String lastSection = joinLines(lines, sectionStart, lines.length - 1);
        if (!lastSection.isBlank()) {
            chunks.add(Chunk.of(lastSection, sectionStart + 1, lines.length));
        }

        // Fallback: if no "## " headers were found, return whole file
        if (chunks.isEmpty()) {
            chunks.add(Chunk.of(content, 1, lines.length));
        }

        return chunks;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Strategy 3 — Generic: whole-file or 150-line sliding pieces
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles all file types that have no semantic structure we can exploit.
     *
     * Rule:
     *   ≤ 150 lines → return the whole file as one chunk
     *   > 150 lines → split into 150-line pieces, NO overlap
     *
     * Why no overlap here (unlike the old line-based approach)?
     *   Overlap is most useful when cutting in the MIDDLE of a statement
     *   (so the context from the previous chunk helps).
     *   For JSON, YAML, HTML etc., 150 lines is usually enough to contain
     *   complete objects/elements, so overlap adds noise without benefit.
     *
     * @param content The full file content
     * @return        A list of fixed-size line-based chunks
     */
    public List<Chunk> chunkGenericFile(String content) {
        String[] lines = content.split("\n", -1);
        List<Chunk> chunks = new ArrayList<>();

        if (lines.length <= GENERIC_CHUNK_SIZE) {
            // Small file — one chunk for the whole thing
            chunks.add(Chunk.of(content, 1, lines.length));
            return chunks;
        }

        // Large file — split into 150-line pieces
        //
        // i is the 0-based index of the FIRST line of each chunk.
        // We step by GENERIC_CHUNK_SIZE (150) each iteration.
        //
        // Example with 320 lines and GENERIC_CHUNK_SIZE = 150:
        //   Iteration 1: i=0   → lines 1–150
        //   Iteration 2: i=150 → lines 151–300
        //   Iteration 3: i=300 → lines 301–320  (Math.min stops at 319, the last index)
        for (int i = 0; i < lines.length; i += GENERIC_CHUNK_SIZE) {
            // Math.min prevents going past the last line of the array
            int endIndex = Math.min(i + GENERIC_CHUNK_SIZE - 1, lines.length - 1);
            String chunkContent = joinLines(lines, i, endIndex);
            // Convert 0-based indices → 1-based line numbers for citations
            chunks.add(Chunk.of(chunkContent, i + 1, endIndex + 1));
        }

        return chunks;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Joins a range of lines (inclusive on both ends) back into a single
     * String with newlines between them.
     *
     * @param lines      The array of all lines in the file
     * @param startIndex 0-based index of the first line to include
     * @param endIndex   0-based index of the last line to include (inclusive)
     * @return           The joined text
     */
    private String joinLines(String[] lines, int startIndex, int endIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i <= endIndex && i < lines.length; i++) {
            if (i > startIndex) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Pre-computes where each line starts in the full content string.
     *
     * We need this to convert a regex match's character offset (e.g. offset 347)
     * back into a human-readable line number (e.g. "line 12").
     *
     * How it works:
     *   lineStartOffsets[0] = 0          (line 1 always starts at char 0)
     *   lineStartOffsets[1] = 0 + len("line1") + 1    (after the \n)
     *   lineStartOffsets[2] = prev + len("line2") + 1
     *   ...
     *
     * The +1 accounts for the '\n' character itself (it has a position in the
     * content string but we move past it to the start of the next line).
     *
     * @param content    Full file text
     * @param lineCount  Total number of lines (lines.length)
     * @return           int[] where index i holds the char offset of line i+1
     */
    private int[] buildLineStartOffsets(String content, int lineCount) {
        int[] offsets = new int[lineCount];
        offsets[0] = 0; // Line 1 always starts at character 0
        int currentOffset = 0;

        for (int lineIdx = 0; lineIdx < lineCount - 1; lineIdx++) {
            // Find the next '\n' starting from our current position
            int newlinePos = content.indexOf('\n', currentOffset);
            if (newlinePos < 0) break; // No more newlines — last line, no trailing \n
            currentOffset = newlinePos + 1; // +1: skip past the '\n' itself
            offsets[lineIdx + 1] = currentOffset;
        }

        return offsets;
    }

    /**
     * Converts a character offset in the content string → a 1-based line number.
     *
     * Algorithm: the line number is 1 + (how many line-start offsets are ≤ charOffset).
     *
     * We do a simple linear scan (acceptable since files are at most a few hundred
     * lines). For very large files, a binary search would be faster.
     *
     * Example:
     *   lineStartOffsets = [0, 10, 22, 35]  (4 lines)
     *   charOffset = 15
     *   15 >= offsets[0]=0  → yes
     *   15 >= offsets[1]=10 → yes
     *   15 >= offsets[2]=22 → no  → stop
     *   Answer: line 2 (0-based index 1 → 1-indexed line 2) ✓
     *
     * @param charOffset       Character position in the content string
     * @param lineStartOffsets Pre-computed array from buildLineStartOffsets()
     * @return                 1-indexed line number
     */
    private int charOffsetToLineNumber(int charOffset, int[] lineStartOffsets) {
        int line = 1; // Default: line 1
        for (int i = 1; i < lineStartOffsets.length; i++) {
            if (lineStartOffsets[i] <= charOffset) {
                line = i + 1; // i is 0-based, line numbers are 1-based
            } else {
                break; // offsets are sorted ascending — once we overshoot, stop
            }
        }
        return line;
    }
}
