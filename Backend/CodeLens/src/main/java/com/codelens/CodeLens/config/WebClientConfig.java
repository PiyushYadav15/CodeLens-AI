package com.codelens.CodeLens.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Explicit bean registrations needed when both spring-boot-starter-web
 * (Servlet/Tomcat) and spring-boot-starter-webflux are on the classpath.
 *
 * In Spring Boot 4.x, having both stacks present prevents the auto-
 * configuration from registering WebClient.Builder and ObjectMapper beans
 * because it cannot determine which web stack is "primary".
 *
 * This class makes the choice explicit: Tomcat handles incoming REST
 * requests; WebClient is used only for outbound calls (Gemini API, etc.).
 */
@Configuration
public class WebClientConfig {

    /**
     * Provides a WebClient.Builder bean.
     *
     * Spring injects a NEW builder instance each time it is requested,
     * so each consumer (e.g. GeminiEmbeddingClient) can customise its
     * own base URL, headers, and filters without affecting others.
     *
     * @return a fresh WebClient.Builder
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * Provides a shared ObjectMapper bean.
     *
     * ObjectMapper is Jackson's JSON serialiser/deserialiser. Spring Boot
     * normally auto-configures one, but with both web stacks on the
     * classpath the auto-config may not fire. Declaring it here guarantees
     * it is always available for injection (e.g. in GeminiEmbeddingClient).
     *
     * Configuration:
     *   WRITE_DATES_AS_TIMESTAMPS = false → ISO-8601 date strings, not epoch longs
     *
     * @return a configured ObjectMapper
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
