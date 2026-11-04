package com.codelens.CodeLens.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS (Cross-Origin Resource Sharing) configuration.
 *
 * Without this, browsers block requests from the React dev server
 * (http://localhost:5173) to the Spring Boot backend (http://localhost:8080)
 * because they are on different ports = different "origins".
 *
 * This allows the frontend to call the backend in both dev and production.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        // Allow the Vite dev server origin
                        .allowedOrigins(
                                "http://localhost:5173",
                                "http://localhost:3000",
                                "http://localhost:4173"  // Vite preview
                        )
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false)
                        .maxAge(3600); // Cache preflight for 1 hour
            }
        };
    }
}
