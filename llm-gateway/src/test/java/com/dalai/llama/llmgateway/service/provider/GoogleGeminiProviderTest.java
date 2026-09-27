package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleGeminiProviderTest {
    private final GoogleGeminiProvider provider = new GoogleGeminiProvider("http://localhost", "test", 1000);

    @Test
    void searchKeepsGroundingAndJsonInstructionWithoutUnsupportedMimeType() {
        for (Object enabled : List.of(true, "true")) {
            Map<String, Object> body = body(Map.of("response_format", "json", "google_search", enabled,
                    "temperature", 0.4, "max_tokens", 2048), List.of());
            assertThat(body.get("tools")).isEqualTo(List.of(Map.of("google_search", Map.of())));
            assertThat(body.get("generationConfig")).isEqualTo(Map.of("temperature", 0.4, "maxOutputTokens", 2048));
            assertThat(body.get("systemInstruction").toString()).contains("Original instructions", "valid JSON");
        }
    }

    @Test
    void plainJsonRetainsStrictMimeMode() {
        Map<String, Object> body = body(Map.of("response_format", "json", "google_search", false), List.of());
        assertThat(body.get("generationConfig")).isEqualTo(Map.of("responseMimeType", "application/json"));
        assertThat(body).doesNotContainKey("tools");
    }

    @Test
    void functionToolsAlsoAvoidUnsupportedMimeMode() {
        Map<String, Object> body = body(Map.of("response_format", "json"),
                List.of(new ToolDefinition("lookup", "Lookup", Map.of("type", "object"))));
        assertThat(body).containsKey("tools").doesNotContainKey("generationConfig");
        assertThat(body.get("systemInstruction").toString()).contains("valid JSON");
    }

    @Test
    void searchWithoutJsonDoesNotAddJsonInstructions() {
        Map<String, Object> body = body(Map.of("google_search", true), List.of());
        assertThat(body).containsKey("tools").doesNotContainKey("generationConfig");
        assertThat(body.get("systemInstruction").toString()).doesNotContain("valid JSON");
    }

    private Map<String, Object> body(Map<String, Object> params, List<ToolDefinition> tools) {
        return provider.toGeminiRequestBody(new CanonicalRequest("gemini-2.5-flash", "text",
                List.of(new ChatMessage("system", "Original instructions"), new ChatMessage("user", "Generate ideas")),
                params, 1000, tools));
    }
}
