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

    /** Templated calls send the rendered template as the system message and an empty user
     * message; Gemini answered those with 400 "Request has empty input". */
    @Test
    @SuppressWarnings("unchecked")
    void aTemplatedCallWithAnEmptyUserMessageSendsTheTemplateAsTheRequest() {
        Map<String, Object> body = provider.toGeminiRequestBody(new CanonicalRequest("gemini-2.5-flash", "text",
                List.of(new ChatMessage("system", "Plan the score"), new ChatMessage("user", "")),
                Map.of("response_format", "json"), 1000, List.of()));

        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0)).isEqualTo(Map.of("role", "user", "parts", List.of(Map.of("text", "Plan the score"))));
        assertThat(body).doesNotContainKey("systemInstruction");
        assertThat(body.toString()).doesNotContain("text=,").doesNotContain("text=}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyTextBesideImagesIsLeftOutAndTheInstructionsStaySystem() {
        Map<String, Object> body = provider.toGeminiRequestBody(new CanonicalRequest("gemini-2.5-flash", "text",
                List.of(new ChatMessage("system", "Describe"), new ChatMessage("user", " ", null, null, List.of("data:image/png;base64,AAAA"))),
                Map.of(), 1000, List.of()));

        List<Map<String, Object>> parts = (List<Map<String, Object>>) ((List<Map<String, Object>>) body.get("contents")).get(0).get("parts");
        assertThat(parts).hasSize(1).allSatisfy(part -> assertThat(part).containsKey("inlineData"));
        assertThat(body.get("systemInstruction").toString()).contains("Describe");
    }

    private Map<String, Object> body(Map<String, Object> params, List<ToolDefinition> tools) {
        return provider.toGeminiRequestBody(new CanonicalRequest("gemini-2.5-flash", "text",
                List.of(new ChatMessage("system", "Original instructions"), new ChatMessage("user", "Generate ideas")),
                params, 1000, tools));
    }
}
