package com.codelens.CodeLens.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChunkingService#chunkJavaFile(String)}.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHAT IS "ARRANGE, ACT, ASSERT" (AAA)?
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  Every test you write should answer THREE questions:
 *
 *  1. ARRANGE  — Set up everything the code under test needs to run.
 *                Create objects, define input values, configure mocks.
 *                Think: "What does the world look like BEFORE I call the method?"
 *
 *  2. ACT      — Call the method / piece of code you actually want to test.
 *                This should ideally be exactly ONE line. If you need many
 *                lines to "act", you are probably testing too much at once.
 *
 *  3. ASSERT   — Verify that what happened matches what you expected.
 *                Use JUnit's assertEquals(), assertTrue(), assertNotNull(), etc.
 *                If the assert fails, the test is marked RED (failing).
 *
 *  ─── Why this structure? ───────────────────────────────────────────────────
 *  It forces you to:
 *   • Keep tests focused on ONE behavior at a time.
 *   • Make failing tests easy to diagnose ("which part failed?").
 *   • Communicate intent to future readers — each test tells a small story.
 *
 *  ─── Example in plain English: ────────────────────────────────────────────
 *  Test: "A calculator's add() method should return 5 when given 2 and 3."
 *
 *    // ARRANGE — set up inputs
 *    int a = 2, b = 3;
 *
 *    // ACT — call the method being tested
 *    int result = calculator.add(a, b);
 *
 *    // ASSERT — verify the output
 *    assertEquals(5, result);
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  NOTE: ChunkingService has NO Spring dependencies — it is a pure Java class.
 *  That means we can create it with "new ChunkingService()" and test it
 *  without starting Spring Boot. This makes tests run in milliseconds!
 */
class ChunkingServiceTest {

    // The object we are testing — called the "System Under Test" (SUT)
    private ChunkingService chunkingService;

    /**
     * @BeforeEach runs this setup method BEFORE every single @Test method.
     * It ensures each test gets a fresh, clean instance — tests cannot
     * accidentally influence each other through shared state.
     */
    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: A file with exactly 2 methods → should produce exactly 2 chunks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @DisplayName gives the test a human-readable label shown in test reports.
     * Much better than reading "chunkJavaFile_twoMethods_returnsTwoChunks".
     */
    @Test
    @DisplayName("chunkJavaFile() → 2 methods → returns exactly 2 chunks")
    void chunkJavaFile_withTwoMethods_returnsExactlyTwoChunks() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // We craft a tiny but realistic Java class string with exactly 2 methods.
        //
        // The layout:
        //   Line 1:  public class Calculator {
        //   Line 2:  (blank)
        //   Line 3:      public int add(int a, int b) {
        //   Line 4:          return a + b;
        //   Line 5:      }
        //   Line 6:  (blank)
        //   Line 7:      public int subtract(int a, int b) {
        //   Line 8:          return a - b;
        //   Line 9:      }
        //   Line 10: }
        //
        // We deliberately name the methods so we can identify which chunk is which.
        String sampleJavaClass =
                "public class Calculator {\n" +               // line 1
                "\n" +                                        // line 2
                "    public int add(int a, int b) {\n" +     // line 3
                "        return a + b;\n" +                   // line 4
                "    }\n" +                                   // line 5
                "\n" +                                        // line 6
                "    public int subtract(int a, int b) {\n" + // line 7
                "        return a - b;\n" +                   // line 8
                "    }\n" +                                   // line 9
                "}\n";                                        // line 10

        // ── ACT ──────────────────────────────────────────────────────────────
        // Call the method we want to test with our prepared input.
        List<Chunk> chunks = chunkingService.chunkJavaFile(sampleJavaClass);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // First: verify the correct NUMBER of chunks was produced.
        // assertNotNull prevents a NullPointerException before size() is called.
        assertNotNull(chunks, "The returned chunk list must never be null");
        assertEquals(2, chunks.size(),
                "Expected exactly 2 chunks (one per method), but got: " + chunks.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: First chunk covers the "add" method on lines 3–5
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chunkJavaFile() → first chunk covers 'add' method, lines 3–5")
    void chunkJavaFile_firstChunk_coversAddMethodWithCorrectLineNumbers() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Same sample class as above. Repeated in each test so tests are
        // self-contained and can be read independently.
        String sampleJavaClass =
                "public class Calculator {\n" +               // line 1
                "\n" +                                        // line 2
                "    public int add(int a, int b) {\n" +     // line 3
                "        return a + b;\n" +                   // line 4
                "    }\n" +                                   // line 5
                "\n" +                                        // line 6
                "    public int subtract(int a, int b) {\n" + // line 7
                "        return a - b;\n" +                   // line 8
                "    }\n" +                                   // line 9
                "}\n";                                        // line 10

        // ── ACT ──────────────────────────────────────────────────────────────
        List<Chunk> chunks = chunkingService.chunkJavaFile(sampleJavaClass);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // The ChunkingService scans methods in order, so index 0 = "add".
        Chunk addChunk = chunks.get(0);

        // Verify the method name captured during chunking
        assertEquals("add", addChunk.methodName(),
                "First chunk should be named 'add'");

        // Verify the start line: "public int add(int a, int b) {" is on line 3
        assertEquals(3, addChunk.startLine(),
                "add() starts on line 3");

        // Verify the end line: the closing '}' of add() is on line 5
        assertEquals(5, addChunk.endLine(),
                "add() ends on line 5");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Second chunk covers the "subtract" method on lines 7–9
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chunkJavaFile() → second chunk covers 'subtract' method, lines 7–9")
    void chunkJavaFile_secondChunk_coversSubtractMethodWithCorrectLineNumbers() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        String sampleJavaClass =
                "public class Calculator {\n" +               // line 1
                "\n" +                                        // line 2
                "    public int add(int a, int b) {\n" +     // line 3
                "        return a + b;\n" +                   // line 4
                "    }\n" +                                   // line 5
                "\n" +                                        // line 6
                "    public int subtract(int a, int b) {\n" + // line 7
                "        return a - b;\n" +                   // line 8
                "    }\n" +                                   // line 9
                "}\n";                                        // line 10

        // ── ACT ──────────────────────────────────────────────────────────────
        List<Chunk> chunks = chunkingService.chunkJavaFile(sampleJavaClass);

        // ── ASSERT ───────────────────────────────────────────────────────────
        Chunk subtractChunk = chunks.get(1);

        assertEquals("subtract", subtractChunk.methodName(),
                "Second chunk should be named 'subtract'");

        assertEquals(7, subtractChunk.startLine(),
                "subtract() starts on line 7");

        assertEquals(9, subtractChunk.endLine(),
                "subtract() ends on line 9");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Edge case — no methods → falls back to one chunk for the whole file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chunkJavaFile() → interface with no methods → single whole-file fallback chunk")
    void chunkJavaFile_withNoMethods_returnsOneWholeFileChunk() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // An interface has no method BODIES — just signatures ending in ';'.
        // The regex requires a '{', so nothing matches → fallback kicks in.
        String interfaceContent =
                "public interface Greeter {\n" +
                "    String greet(String name);\n" +  // no '{' → not matched
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        List<Chunk> chunks = chunkingService.chunkJavaFile(interfaceContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // Fallback: if no methods are found, the whole file is ONE chunk.
        assertEquals(1, chunks.size(),
                "An interface with no method bodies should produce exactly 1 fallback chunk");

        // That single chunk should span from line 1 to the last line of the file.
        assertEquals(1, chunks.get(0).startLine(), "Fallback chunk must start at line 1");
    }
}
