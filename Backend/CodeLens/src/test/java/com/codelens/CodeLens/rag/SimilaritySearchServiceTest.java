package com.codelens.CodeLens.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SimilaritySearchService#cosineSimilarity(float[], float[])}.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  ARRANGE, ACT, ASSERT — A REMINDER
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  ARRANGE  → Prepare the objects and data you need
 *  ACT      → Call the one method you are testing
 *  ASSERT   → Verify the result is what you expected
 *
 *  These three phases turn every test into a small, self-contained story
 *  that is easy to read, diagnose, and maintain.
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  WHY NO SPRING HERE?
 *  SimilaritySearchService needs two Spring beans (CodeChunkRepository and
 *  ObjectMapper) via its constructor. The cosineSimilarity() method doesn't
 *  use either of those fields, so we use Mockito to create lightweight
 *  stand-ins and inject them — no Spring context, no database, instant tests.
 *
 *  We use org.mockito.Mockito.mock() which creates a "mock" object — a dummy
 *  that satisfies the type requirement but does nothing on its own.
 *  This is the simplest possible approach for testing a pure math method.
 */
class SimilaritySearchServiceTest {

    // The object we are testing (System Under Test)
    private SimilaritySearchService similaritySearchService;

    @BeforeEach
    void setUp() {
        // We pass null for dependencies because cosineSimilarity() is a pure
        // math function — it doesn't call codeChunkRepository or objectMapper.
        // If it did, we'd need Mockito mocks. For this pure method, null is safe
        // and keeps the test dead simple.
        similaritySearchService = new SimilaritySearchService(null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Identical vectors → cosine similarity must equal 1.0
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Math intuition:
     *   cos(θ) = (A · B) / (||A|| * ||B||)
     *
     *   When A == B:
     *     Dot product    = a[0]² + a[1]² + a[2]²  (same as normA squared)
     *     Magnitude of A = sqrt(a[0]² + a[1]² + a[2]²)
     *     Magnitude of B = same as A (because B == A)
     *
     *   So: cos = (||A||²) / (||A|| * ||A||) = 1.0  ✓
     */
    @Test
    @DisplayName("cosineSimilarity() → identical vectors → returns ~1.0")
    void cosineSimilarity_identicalVectors_returnsOne() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Both vectors are exactly the same. In a real embedding model this
        // represents "the same semantic meaning" — a query asking about cats and
        // a code chunk also about cats (conceptually).
        float[] vectorA = {0.5f, 0.3f, 0.8f, 0.1f};
        float[] vectorB = {0.5f, 0.3f, 0.8f, 0.1f}; // identical copy of A

        // ── ACT ──────────────────────────────────────────────────────────────
        double result = similaritySearchService.cosineSimilarity(vectorA, vectorB);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // We use assertEquals with a delta (tolerance) of 1e-6 because floating-
        // point arithmetic can produce tiny rounding errors like 0.9999999999
        // instead of a perfect 1.0. The delta says "close enough".
        assertEquals(1.0, result, 1e-6,
                "Two identical vectors must have cosine similarity of 1.0 (they point in the exact same direction)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Orthogonal vectors → cosine similarity must equal 0.0
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Math intuition:
     *   Orthogonal means "at right angles" (90°) in the vector space.
     *
     *   A simple example in 2D:
     *     A = [1, 0]  (points along the X-axis)
     *     B = [0, 1]  (points along the Y-axis)
     *
     *   Dot product = (1*0) + (0*1) = 0
     *   cos(90°) = 0 / (1 * 1) = 0.0  ✓
     *
     *   In embedding space, score ≈ 0.0 means the two texts have
     *   completely unrelated meaning.
     */
    @Test
    @DisplayName("cosineSimilarity() → orthogonal vectors → returns ~0.0")
    void cosineSimilarity_orthogonalVectors_returnsZero() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // Classic orthogonal pair:
        //   A lies entirely along the first dimension.
        //   B lies entirely along the second dimension.
        //   Their dot product is (1*0) + (0*1) = 0 → cos = 0.0
        float[] vectorA = {1.0f, 0.0f};
        float[] vectorB = {0.0f, 1.0f};

        // ── ACT ──────────────────────────────────────────────────────────────
        double result = similaritySearchService.cosineSimilarity(vectorA, vectorB);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(0.0, result, 1e-6,
                "Orthogonal vectors (90° apart) must have cosine similarity of 0.0 (completely unrelated)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Zero vector edge case → must return 0.0 (not NaN / exception)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cosineSimilarity() → zero vector input → returns 0.0 (no NaN)")
    void cosineSimilarity_zeroVector_returnsZeroSafely() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // A zero vector has no direction. Division by zero would produce NaN.
        // ChunkingService guards against this — let's confirm it works.
        float[] zeroVector  = {0.0f, 0.0f, 0.0f};
        float[] normalVector = {0.5f, 0.1f, 0.9f};

        // ── ACT ──────────────────────────────────────────────────────────────
        double result = similaritySearchService.cosineSimilarity(zeroVector, normalVector);

        // ── ASSERT ───────────────────────────────────────────────────────────
        // Must return 0.0 — NOT NaN — because the guard clause catches zero magnitude.
        assertEquals(0.0, result, 1e-6,
                "A zero vector has no direction, so similarity is 0.0 (guarded against NaN division)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Mismatched vector lengths → must throw IllegalArgumentException
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cosineSimilarity() → dimension mismatch → throws IllegalArgumentException")
    void cosineSimilarity_mismatchedDimensions_throwsIllegalArgumentException() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        float[] threeD = {1.0f, 2.0f, 3.0f};      // 3-dimensional vector
        float[] twoD   = {1.0f, 2.0f};             // 2-dimensional vector

        // ── ACT & ASSERT ─────────────────────────────────────────────────────
        // assertThrows() verifies that calling the lambda THROWS the expected
        // exception type. If no exception is thrown (or a different exception),
        // the test fails. This is the correct way to test error conditions.
        assertThrows(
                IllegalArgumentException.class,
                () -> similaritySearchService.cosineSimilarity(threeD, twoD),
                "Vectors with different dimensions must throw IllegalArgumentException"
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Anti-parallel vectors → cosine similarity ≈ –1.0
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cosineSimilarity() → anti-parallel vectors → returns ~-1.0")
    void cosineSimilarity_antiParallelVectors_returnsNegativeOne() {

        // ── ARRANGE ──────────────────────────────────────────────────────────
        // A points in the positive direction; B points in exactly the opposite direction.
        // cos(180°) = -1.0
        // (Rare in real embedding models, but the math must hold.)
        float[] vectorA = { 1.0f,  1.0f};
        float[] vectorB = {-1.0f, -1.0f}; // exact opposite of A

        // ── ACT ──────────────────────────────────────────────────────────────
        double result = similaritySearchService.cosineSimilarity(vectorA, vectorB);

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertEquals(-1.0, result, 1e-6,
                "Anti-parallel vectors (180° apart) must have cosine similarity of -1.0");
    }
}
