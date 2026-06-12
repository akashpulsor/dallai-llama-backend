package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiCreatorAiProviderSearchGroundingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsGoogleSearchToolAndParsesGroundingMetadata() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        HttpServer server = startGeminiStub(capturedRequest);
        try {
            CreatorProperties properties = new CreatorProperties();
            properties.getAi().setGeminiApiKey("test-key");
            properties.getAi().setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getAi().setGeminiModel("gemini-2.5-flash");
            properties.getAi().setMaxOutputTokens(1024);

            GeminiCreatorAiProvider provider = new GeminiCreatorAiProvider(
                    properties,
                    objectMapper,
                    new GeminiUsageMetadataParser(objectMapper),
                    new GoogleGenAiClientFactory(properties, WebClient.builder()),
                    new GeminiRateLimitGuard(properties)
            );

            Map<String, Object> output = provider.generate("WEEKLY_IDEA_TAGS", Map.of(
                    "renderedPrompt", "Use Google Search grounding and return current creator idea tags.",
                    "useGoogleSearch", true
            ));

            assertThat(output)
                    .containsEntry("topic", "India cricket creator angle")
                    .containsEntry("groundedWithGoogleSearch", true);
            assertThat(output.get("groundingMetadata")).asList().hasSize(1);

            Map<String, Object> request = objectMapper.readValue(capturedRequest.get(), new TypeReference<>() {
            });
            List<Map<String, Object>> tools = listOfMaps(request.get("tools"));
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0)).containsKey("google_search");

            Map<String, Object> generationConfig = mapValue(request.get("generationConfig"));
            assertThat(generationConfig).doesNotContainKey("responseMimeType");
            assertThat(generationConfig).containsEntry("maxOutputTokens", 1024);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startGeminiStub(AtomicReference<String> capturedRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "{\\"topic\\":\\"India cricket creator angle\\",\\"sourceHint\\":\\"Google Search grounding signal\\",\\"confidence\\":\\"medium\\"}"
                              }
                            ]
                          },
                          "finishReason": "STOP",
                          "groundingMetadata": {
                            "webSearchQueries": ["India cricket news"],
                            "groundingChunks": [
                              {
                                "web": {
                                  "title": "Search result",
                                  "uri": "https://example.com/search-result"
                                }
                              }
                            ]
                          }
                        }
                      ],
                      "usageMetadata": {
                        "promptTokenCount": 12,
                        "candidatesTokenCount": 8,
                        "totalTokenCount": 20
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        return server;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
