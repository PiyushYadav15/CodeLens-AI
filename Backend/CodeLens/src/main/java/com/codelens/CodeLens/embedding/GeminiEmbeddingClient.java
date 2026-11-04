package com.codelens.CodeLens.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * Calls Google's Gemini text-embedding-004 API to convert a piece of
 * text into an embedding vector (a list of floating-point numbers).
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHAT IS AN EMBEDDING? (Plain English, for beginners)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * You already know it's "numbers representing meaning". Here's a bit more:
 *
 * Imagine every possible sentence lives somewhere in a giant space with
 * 768 dimensions (one per number in the embedding). Sentences that mean
 * similar things are placed NEAR each other in that space; sentences that
 * mean very different things are placed FAR apart.
 *
 * Real examples of what "near" means:
 *   "How do I authenticate a user?"
 *   "Where is the login code?"
 *       → These two questions have very similar embeddings, so they land
 *         close together even though the exact words are different.
 *
 *   "How do I authenticate a user?"
 *   "SELECT * FROM orders WHERE total > 100"
 *       → Completely different topics → far apart in embedding space.
 *
 * HOW WE USE THIS IN CODELENS:
 *   1. We embed each CodeChunk (a Java method, a Markdown section, etc.)
 *      and store the resulting vector in CodeChunk.embedding.
 *   2. When the user asks a question, we embed THEIR QUESTION too.
 *   3. We find the chunks whose embeddings are CLOSEST to the question's
 *      embedding — those are the most relevant code sections.
 *   4. We send those chunks to Gemini as context and let it answer the
 *      question with precise source citations.
 *
 * This is the RAG (Retrieval-Augmented Generation) pattern.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  HOW TO GET A FREE GEMINI API KEY
 * ════════════════════════════════════════════════════════════════════════════
 *
 *   1. Go to https://aistudio.google.com/
 *   2. Sign in with your Google account
 *   3. Click "Get API key" (top-left)
 *   4. Click "Create API key in new project"
 *   5. Copy the key that appears
 *   6. Paste it into application.properties:
 *        app.gemini.api-key=AIzaSy...your-key-here...
 *
 *   The free tier gives you 1,500 requests per day — plenty for development.
 *   Rate limit on free tier: 15 requests per minute (we add a 200ms sleep
 *   in EmbeddingIndexingService to stay safely under that).
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  HOW WEBCLIENT WORKS (vs old RestTemplate)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WebClient is Spring's modern HTTP client (introduced in Spring 5).
 * It's part of WebFlux and is non-blocking by design — it doesn't tie
 * up a server thread while waiting for the API to respond.
 *
 * A WebClient call is a FLUENT PIPELINE:
 *
 *   webClient
 *       .post()                          // HTTP method
 *       .uri("https://...")             // URL
 *       .contentType(MediaType.APPLICATION_JSON)
 *       .bodyValue(requestBody)          // what to send
 *       .retrieve()                      // execute the request
 *       .bodyToMono(String.class)        // read response as a String
 *       .block();                        // WAIT for the result (makes it synchronous)
 *
 * WHY .block() HERE?
 *   WebClient is naturally async (reactive). .block() turns it back into a
 *   regular synchronous call. We use .block() because our EmbeddingIndexingService
 *   processes chunks one at a time in a loop — we WANT to wait for each
 *   API response before moving to the next chunk.
 *   (Full reactive programming would avoid .block(), but that's an advanced topic.)
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class GeminiEmbeddingClient {

    // ── Fields ────────────────────────────────────────────────────────────

    /**
     * The WebClient instance, pre-configured with the Gemini base URL.
     * We create it in the constructor (not as a @Bean) because we need
     * the API key (which is injected by @Value) to build the URL.
     */
    private final WebClient webClient;

    /**
     * Jackson's ObjectMapper — converts between Java objects and JSON strings.
     *
     * We use it in two places:
     *   1. To build the request body JSON
     *   2. To parse the response JSON and extract the embedding array
     *
     * Spring Boot auto-configures one ObjectMapper bean; we inject it here.
     * Using the shared bean is better than creating a new ObjectMapper()
     * because the shared one has all of Spring's configuration (date formats etc).
     */
    private final ObjectMapper objectMapper;

    /**
     * The Gemini API key, read from application.properties.
     *
     * @Value("${app.gemini.api-key}")
     *   — Spring reads the value of the property key "app.gemini.api-key"
     *     from application.properties and injects it as a String here.
     *     The ${...} syntax is called a Property Placeholder.
     *
     * IMPORTANT: Never commit a real API key to git.
     * For production, use environment variables or a secrets manager.
     * For now, just put your key in application.properties locally.
     */
    @Value("${app.gemini.api-key}")
    private String apiKey;

    /** The Gemini model name for embeddings (configurable) */
    @Value("${app.gemini.embedding-model:text-embedding-004}")
    private String embeddingModel;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * We inject the ObjectMapper and create the WebClient here.
     * WebClient.builder() is Spring's factory — it creates a pre-configured
     * client that shares connection pools and settings across the app.
     */
    public GeminiEmbeddingClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Calls the Gemini embedding API for the given text and returns the
     * embedding as a JSON string like "[0.012, -0.44, 0.003, ...]".
     *
     * Returns Optional.empty() if the call fails — the caller should
     * skip this chunk rather than crashing.
     *
     * API endpoint:
     *   POST /v1beta/models/{model}:embedContent?key={API_KEY}
     *
     * Request body:
     *   { "content": { "parts": [{ "text": "code text here" }] } }
     *
     * Response body:
     *   { "embedding": { "values": [0.012, -0.44, 0.003, ...] } }
     *
     * @param text The chunk content to embed (a Java method, markdown section, etc.)
     * @return     The embedding as a JSON string, or Optional.empty() on error
     */
    public Optional<String> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        try {
            // ── Step 1: Build the request body as a JSON string ─────────────
            //
            // We need to send:
            //   { "content": { "parts": [{ "text": "..." }] } }
            //
            // We use ObjectMapper to build this safely — it handles escaping
            // special characters in `text` (quotes, backslashes, newlines, etc.)
            // that would break the JSON if we used string concatenation.
            //
            // objectMapper.createObjectNode() creates a blank {} JSON object.
            // .put("key", value) adds a field.
            // .set("key", node)  adds a nested object.
            //
            var contentNode = objectMapper.createObjectNode();
            var partsArray  = objectMapper.createArrayNode();
            var partNode    = objectMapper.createObjectNode();

            partNode.put("text", text);
            partsArray.add(partNode);
            contentNode.set("parts", partsArray);

            var requestBody = objectMapper.createObjectNode();
            requestBody.set("content", contentNode);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            // requestBodyJson is now: {"content":{"parts":[{"text":"..."}]}}

            // ── Step 2: Build the URL ────────────────────────────────────────
            // The API key goes in the QUERY STRING (not a header) for Gemini.
            // Path: /v1beta/models/text-embedding-004:embedContent?key=YOUR_KEY
            String path = "/v1beta/models/" + embeddingModel + ":embedContent";

            // ── Step 3: Make the HTTP POST call ─────────────────────────────
            String responseJson = webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("key", apiKey) // ?key=YOUR_KEY
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBodyJson)
                    .retrieve()
                    // .retrieve() executes the request.
                    // If the server returns 4xx or 5xx, it throws WebClientResponseException.
                    .bodyToMono(String.class)
                    // bodyToMono wraps the response body in a "Mono" (a reactive container
                    // that will hold exactly 0 or 1 result). Think of it as a Promise.
                    .block();
                    // .block() waits synchronously for the Mono to produce its value.
                    // This is the response JSON as a plain String.

            // ── Step 4: Parse the response JSON ─────────────────────────────
            // We need to extract the float array from:
            //   { "embedding": { "values": [0.012, -0.44, ...] } }
            //
            // objectMapper.readTree() parses the JSON into a tree of JsonNodes.
            // Each JsonNode represents one JSON value (object, array, string, etc.)
            // We navigate the tree with .get("fieldName").
            return Optional.of(extractEmbeddingJson(responseJson));

        } catch (WebClientResponseException e) {
            // WebClientResponseException is thrown when the server returns
            // an HTTP error code (4xx or 5xx).
            // e.getStatusCode() → e.g. 429 TOO_MANY_REQUESTS
            // e.getResponseBodyAsString() → the error body from Gemini
            log.warn("Gemini API error: HTTP {} — {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty(); // Skip this chunk; don't crash

        } catch (JsonProcessingException e) {
            // Thrown if we fail to build the request JSON or parse the response.
            // Should not happen with valid input — log and skip.
            log.warn("JSON processing error for embedding request: {}", e.getMessage());
            return Optional.empty();

        } catch (Exception e) {
            // Catch-all: network timeouts, DNS failures, etc.
            log.warn("Unexpected error calling Gemini embedding API: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Parses the Gemini API response and converts the float array into
     * a compact JSON string suitable for storage in CodeChunk.embedding.
     *
     * Input JSON:
     *   { "embedding": { "values": [0.012, -0.44, 0.003, ...] } }
     *
     * Output string:
     *   "[0.012,-0.44,0.003,...]"
     *
     * We re-serialize just the "values" array (not the whole response),
     * because we only want to store the float array — the surrounding
     * "embedding" and "values" keys are not needed in the DB.
     *
     * @param responseJson The raw JSON string from the Gemini API
     * @return             The values array serialised as a compact JSON string
     * @throws Exception   If the response doesn't have the expected structure
     */
    private String extractEmbeddingJson(String responseJson) throws Exception {
        JsonNode root        = objectMapper.readTree(responseJson);
        JsonNode valuesNode  = root.path("embedding").path("values");
        // .path() is like .get() but returns a "MissingNode" instead of null
        // when the key doesn't exist — safer to chain.

        if (valuesNode.isMissingNode() || !valuesNode.isArray()) {
            throw new IllegalStateException(
                    "Gemini response missing 'embedding.values' array: " + responseJson);
        }

        // Re-serialize just the values array back into a compact JSON string.
        // e.g.  [0.012,-0.44,0.003,...]
        // This is what we store in the TEXT column of code_chunk.
        return objectMapper.writeValueAsString(valuesNode);
    }

    /**
     * Parses a stored embedding JSON string back into a Java double array.
     * Used during similarity search in the RAG phase.
     *
     * Input string (from DB):  "[0.012,-0.44,0.003,...]"
     * Output:                  double[] { 0.012, -0.44, 0.003, ... }
     *
     * @param embeddingJson The JSON string stored in CodeChunk.embedding
     * @return              A double array of the embedding values
     * @throws Exception    If the string is not a valid JSON float array
     */
    public double[] parseEmbedding(String embeddingJson) throws Exception {
        JsonNode arrayNode = objectMapper.readTree(embeddingJson);
        if (!arrayNode.isArray()) {
            throw new IllegalArgumentException("Expected a JSON array, got: " + embeddingJson);
        }

        // Convert the JSON array → double[]
        double[] values = new double[arrayNode.size()];
        for (int i = 0; i < arrayNode.size(); i++) {
            values[i] = arrayNode.get(i).asDouble();
        }
        return values;
    }
}
