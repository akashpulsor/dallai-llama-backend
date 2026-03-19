package com.dalai.llama.pbx.core.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Configuration for Jackson JSON serialization/deserialization.
 */
@Configuration
public class JacksonConfig {

    /**
     * Customizes the primary ObjectMapper to use snake_case and support Java 8 Date/Time API.
     */
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder
                // Apply snake_case naming strategy
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // Register module for JSR-310 (Java 8 Date/Time like LocalDateTime, Instant)
                .modulesToInstall(new JavaTimeModule())
                // Ensure dates are serialized as ISO-8601 strings rather than timestamp arrays
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
