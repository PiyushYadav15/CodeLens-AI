package com.codelens.CodeLens.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Client service for calling Groq's ultra-fast LLM inference API.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHAT IS GROQ AND WHY USE IT?
 * ════════════════════════════════════════════════════════════════════════════
 * Groq runs open-weights models (like Meta's Llama 3.3 70B) on specialized
 * LPUs (Language Processing Units). It generates responses at 250–500 tokens
 * per second — virtually instant answers.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  HOW TO GET A FREE GROQ API KEY
 * ════════════════════════════════════════════════════════════════════════════
 *  1. Go to https://console.groq.com/
 *  2. Sign in with your Google or GitHub account
 *  3. In the left menu, click "API Keys" (or go to https://console.groq.com/keys)
 *  4. Click "Create API Key", give it a name (e.g., "codelens")
 *  5. Copy the generated key (starts with "gsk_...")
 *  6. Paste it in application.properties:
 *       app.groq.api-key=gsk_your_key_here
 *
 * The free tier is generous (thousands of free requests per day), making it
 * ideal for local development and portfolio projects.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHY TEMPERATURE = 0.2?
 * ════════════════════════════════════════════════════════════════════════════
 * "Temperature" controls how creative vs. deterministic the AI model is:
 *  • Higher (0.8 - 1.0): Creative, diverse, but prone to guessing and hallucinations.
 *  • Lower (0.0 - 0.2): Precise, factual, and strictly adheres to the provided context.
 * For technical Q&A on real code, 0.2 ensures the model sticks strictly to facts.
 * ════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class GroqChatClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.groq.api-key}")
    private String apiKey;

    @Value("${app.groq.chat-model:llama-3.3-70b-versatile}")
    private String chatModel;

    public GroqChatClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.groq.com/openai/v1")
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Sends a completed prompt to Groq's Chat Completions API and returns
     * the generated text response.
     *
     * @param prompt The complete prompt containing context and user question
     * @return The AI's answer string
     */
    public String chat(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "No prompt provided.";
        }

        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("YOUR_")) {
            log.warn("Groq API key is not configured in application.properties (app.groq.api-key)");
            return "Groq API key is missing or not configured. Please set 'app.groq.api-key' in application.properties to get AI responses.";
        }

        try {
            // ── Step 1: Build Request JSON ───────────────────────────────────
            // Format:
            // {
            //   "model": "llama-3.3-70b-versatile",
            //   "messages": [
            //     { "role": "user", "content": "..." }
            //   ],
            //   "temperature": 0.2
            // }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", chatModel);
            root.put("temperature", 0.2);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            root.set("messages", messages);

            String requestJson = objectMapper.writeValueAsString(root);

            // ── Step 2: Make the POST Request ────────────────────────────────
            String responseJson = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // Synchronous execution for RAG endpoint

            // ── Step 3: Extract the Answer Text ──────────────────────────────
            // Response format:
            // {
            //   "choices": [
            //     {
            //       "message": {
            //         "role": "assistant",
            //         "content": "Answer here..."
            //       }
            //     }
            //   ]
            // }
            JsonNode responseRoot = objectMapper.readTree(responseJson);
            JsonNode contentNode = responseRoot.path("choices")
                    .path(0)
                    .path("message")
                    .path("content");

            if (contentNode.isMissingNode()) {
                log.error("Unexpected Groq response structure: {}", responseJson);
                return "Received unexpected response format from Groq API.";
            }

            return contentNode.asText();

        } catch (WebClientResponseException e) {
            log.error("Groq API HTTP error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return "Error calling Groq API: HTTP " + e.getStatusCode().value() + " - " + e.getStatusText();
        } catch (Exception e) {
            log.error("Error calling Groq chat completions: {}", e.getMessage(), e);
            return "Failed to generate answer due to an internal error: " + e.getMessage();
        }
    }
}
