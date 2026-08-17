package com.dalai.llama.creator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for ProviderStoragePayloadSanitizer - the extraction of
 * ScreenplayVideoService's provider-storage payload sanitization cluster.
 */
class ProviderStoragePayloadSanitizerTest {

    private final ProviderStoragePayloadSanitizer sanitizer = new ProviderStoragePayloadSanitizer(new ObjectMapper());

    @Test
    void sanitizeProviderStorageMap_returnsEmptyMapForNullOrEmptyInput() {
        assertTrue(sanitizer.sanitizeProviderStorageMap(null).isEmpty());
        assertTrue(sanitizer.sanitizeProviderStorageMap(Map.of()).isEmpty());
    }

    @Test
    void sanitizeProviderStorageMap_redactsCredentialShapedKeysRegardlessOfCasingOrSeparators() {
        Map<String, Object> payload = Map.of(
                "Authorization", "Bearer xyz",
                "api_key", "sk-123",
                "clientSecret", "shh",
                "accessToken", "tok-123",
                "runId", "run-1"
        );
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        assertEquals("[REDACTED]", sanitized.get("Authorization"));
        assertEquals("[REDACTED]", sanitized.get("api_key"));
        assertEquals("[REDACTED]", sanitized.get("clientSecret"));
        assertEquals("[REDACTED]", sanitized.get("accessToken"));
        assertEquals("run-1", sanitized.get("runId"));
    }

    @Test
    void sanitizeProviderStorageMap_omitsLongBase64ShapedValuesUnderRecognizedKeys() {
        String longBase64 = "A".repeat(100);
        Map<String, Object> payload = Map.of("audioContent", longBase64);
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        String result = (String) sanitized.get("audioContent");
        assertTrue(result.startsWith("[base64 chars=100"));
    }

    @Test
    void sanitizeProviderStorageMap_keepsShortValuesUnderRecognizedBase64Keys() {
        Map<String, Object> payload = Map.of("audio", "short");
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        assertEquals("short", sanitized.get("audio"));
    }

    @Test
    void sanitizeProviderStorageMap_omitsDataUrlValues() {
        String dataUrl = "data:image/png;base64," + "A".repeat(100);
        Map<String, Object> payload = Map.of("someField", dataUrl);
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        String result = (String) sanitized.get("someField");
        assertTrue(result.startsWith("[data-url chars="));
    }

    @Test
    void sanitizeProviderStorageMap_truncatesVeryLongPlainTextValues() {
        // "!" is not a base64 alphabet character, so this must land in the plain-text truncate
        // branch rather than being caught by the looksLikeBase64 heuristic first (which a repeated
        // alphanumeric character like "x" would trigger, since it IS a valid base64 character).
        String longText = "!".repeat(45000);
        Map<String, Object> payload = Map.of("notes", longText);
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        String result = (String) sanitized.get("notes");
        assertEquals(40000, result.length());
    }

    @Test
    void sanitizeProviderStorageMap_omitsLongTextThatLooksLikeBase64EvenUnderAnUnrecognizedKey() {
        String base64Looking = "x".repeat(21000);
        Map<String, Object> payload = Map.of("notes", base64Looking);
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        String result = (String) sanitized.get("notes");
        assertTrue(result.startsWith("[base64-like chars=21000"));
    }

    @Test
    void sanitizeProviderStorageMap_recursesIntoNestedMapsAndLists() {
        Map<String, Object> payload = Map.of(
                "scenes", List.of(Map.of("apiKey", "leak-me", "id", "scene-1"))
        );
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        List<?> scenes = (List<?>) sanitized.get("scenes");
        Map<?, ?> scene = (Map<?, ?>) scenes.get(0);
        assertEquals("[REDACTED]", scene.get("apiKey"));
        assertEquals("scene-1", scene.get("id"));
    }

    @Test
    void sanitizeProviderStorageMap_leavesOrdinaryShortValuesUntouched() {
        Map<String, Object> payload = Map.of("status", "PLANNED", "sceneNumber", 3);
        Map<String, Object> sanitized = sanitizer.sanitizeProviderStorageMap(payload);
        assertEquals("PLANNED", sanitized.get("status"));
        assertEquals(3, sanitized.get("sceneNumber"));
    }
}
