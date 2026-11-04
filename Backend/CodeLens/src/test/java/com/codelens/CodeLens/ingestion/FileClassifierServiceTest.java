package com.codelens.CodeLens.ingestion;

import com.codelens.CodeLens.model.FileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileClassifierService#classify(String, String)}.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  ARRANGE, ACT, ASSERT — A REMINDER
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  ARRANGE  → Prepare any inputs, objects, or configuration needed.
 *             Think: "What does the world look like BEFORE the call?"
 *
 *  ACT      → Call the single method you are testing — ideally one line.
 *
 *  ASSERT   → Check that the returned value or side-effect is correct.
 *             A failing assertion turns the test RED and prints a clear message.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  FileClassifierService is a pure Spring @Service with NO constructor
 *  dependencies — so we can instantiate it with "new FileClassifierService()"
 *  and run it without starting Spring, without a database, without any mocks.
 *  These tests run in milliseconds.
 */
class FileClassifierServiceTest {

    // System Under Test (SUT)
    private FileClassifierService fileClassifierService;

    /**
     * @BeforeEach creates a fresh service instance before every @Test.
     * This guarantees tests don't share state and can run in any order.
     */
    @BeforeEach
    void setUp() {
        fileClassifierService = new FileClassifierService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Content with @RestController → must classify as CONTROLLER
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → content has @RestController → returns CONTROLLER")
    void classify_contentWithRestController_returnsController() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Simulate the source code of a typical Spring REST controller.
        // The classifier checks file CONTENT first, so annotations are the key signal.
        // We use a generic path ("SomeFile.java") to confirm the decision is
        // driven purely by content, NOT by path heuristics.
        String filePath = "src/main/java/com/example/SomeFile.java";
        String fileContent =
                "package com.example;\n" +
                "\n" +
                "import org.springframework.web.bind.annotation.RestController;\n" +
                "import org.springframework.web.bind.annotation.GetMapping;\n" +
                "\n" +
                "@RestController\n" +                             // ← the key annotation
                "public class UserController {\n" +
                "\n" +
                "    @GetMapping(\"/api/users\")\n" +
                "    public String getUsers() {\n" +
                "        return \"users\";\n" +
                "    }\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        // classify() returns a ClassificationResult record wrapping the FileType.
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // Unwrap the FileType from the result and verify it equals CONTROLLER.
        assertNotNull(result, "classify() must never return null");
        assertEquals(FileType.CONTROLLER, result.fileType(),
                "A file containing @RestController must be classified as CONTROLLER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: @Controller (not @RestController) → still CONTROLLER
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → content has @Controller → returns CONTROLLER")
    void classify_contentWithController_returnsController() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Spring MVC supports both @RestController (REST/JSON) and @Controller
        // (traditional MVC with view templates). Both should map to CONTROLLER.
        String filePath = "src/main/java/com/example/MvcFile.java";
        String fileContent =
                "@Controller\n" +
                "public class MvcController {\n" +
                "    public String index() { return \"index\"; }\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(FileType.CONTROLLER, result.fileType(),
                "A file with @Controller (not @RestController) must also be CONTROLLER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: @RestController → endpoints are extracted from the file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → @RestController with @GetMapping → endpoints list is populated")
    void classify_restControllerWithGetMapping_populatesEndpoints() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // When a file is classified as CONTROLLER, the service also runs endpoint
        // extraction. Let's verify the endpoint is found.
        String filePath = "src/UserController.java";
        String fileContent =
                "@RestController\n" +
                "public class UserController {\n" +
                "    @GetMapping(\"/api/users\")\n" +
                "    public String getUsers() { return \"\"; }\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertNotNull(result.endpoints(),
                "Endpoints list must not be null for a CONTROLLER");
        assertFalse(result.endpoints().isEmpty(),
                "At least one endpoint should be extracted from @GetMapping(\"/api/users\")");
        assertEquals("GET", result.endpoints().get(0).httpMethod(),
                "The HTTP method should be GET");
        assertEquals("/api/users", result.endpoints().get(0).path(),
                "The path should be '/api/users'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Content with @Service → must classify as SERVICE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → content has @Service → returns SERVICE")
    void classify_contentWithService_returnsService() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        String filePath = "src/main/java/com/example/OrderProcessor.java";
        String fileContent =
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "@Service\n" +
                "public class OrderProcessor {\n" +
                "    public void process() { }\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(FileType.SERVICE, result.fileType(),
                "A file annotated with @Service must be classified as SERVICE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Content with @Entity → must classify as ENTITY
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → content has @Entity → returns ENTITY")
    void classify_contentWithEntity_returnsEntity() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        String filePath = "src/main/java/com/example/User.java";
        String fileContent =
                "import jakarta.persistence.Entity;\n" +
                "\n" +
                "@Entity\n" +
                "public class User {\n" +
                "    private Long id;\n" +
                "    private String name;\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(FileType.ENTITY, result.fileType(),
                "A file annotated with @Entity must be classified as ENTITY");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: No known annotations → must classify as OTHER
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → plain utility class with no known annotations → returns OTHER")
    void classify_plainUtilityClass_returnsOther() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // A utility/helper class — no @RestController, @Service, @Repository, @Entity.
        // The path also contains no known path keywords like /controller/ or /service/.
        // Expected: the classifier falls through all checks and returns OTHER.
        String filePath = "src/main/java/com/example/StringUtils.java";
        String fileContent =
                "public class StringUtils {\n" +
                "    public static String capitalize(String s) {\n" +
                "        return s.substring(0, 1).toUpperCase() + s.substring(1);\n" +
                "    }\n" +
                "}\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(FileType.OTHER, result.fileType(),
                "A plain utility class with no Spring annotations must be classified as OTHER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: CONTROLLER takes priority over @Service when both are present
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("classify() → content has both @RestController and @Service → CONTROLLER wins (priority check)")
    void classify_controllerAnnotationTakesPriorityOverService() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Unusual but valid: a class with two annotations.
        // The classifier must check CONTROLLER first (as documented in the code).
        String filePath = "src/main/java/com/example/Hybrid.java";
        String fileContent =
                "@RestController\n" +
                "@Service\n" +                     // unusual, but tests priority
                "public class Hybrid { }\n";

        // ── ACT ──────────────────────────────────────────────────────────────
        ClassificationResult result = fileClassifierService.classify(filePath, fileContent);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(FileType.CONTROLLER, result.fileType(),
                "@RestController must be detected before @Service — CONTROLLER takes priority");
    }
}
