package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.service.AssetStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                    new GeminiRateLimitGuard(properties),
                    WebClient.builder()
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
            List<Map<String, Object>> contents = listOfMaps(request.get("contents"));
            assertThat(listOfMaps(contents.get(0).get("parts")))
                    .singleElement()
                    .satisfies(part -> assertThat(part).containsOnlyKeys("text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void attachesProductReferenceImagesOnlyWhenExplicitlyRequested() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] productImage = new byte[]{1, 2, 3, 4, 5};
        server.createContext("/product.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, productImage.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(productImage);
            }
        });
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "candidates": [{
                        "content": {"parts": [{"text": "{\\"adTone\\":\\"premium and sensory\\"}"}]},
                        "finishReason": "STOP"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        try {
            CreatorProperties properties = new CreatorProperties();
            properties.getAi().setGeminiApiKey("test-key");
            properties.getAi().setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getAi().setGeminiModel("gemini-2.5-flash");

            GeminiCreatorAiProvider provider = new GeminiCreatorAiProvider(
                    properties,
                    objectMapper,
                    new GeminiUsageMetadataParser(objectMapper),
                    new GoogleGenAiClientFactory(properties, WebClient.builder()),
                    new GeminiRateLimitGuard(properties),
                    WebClient.builder()
            );

            Map<String, Object> output = provider.generate("IDEA_GENERATE", Map.of(
                    "renderedPrompt", "Plan a product campaign.",
                    "attachReferenceImages", true,
                    "referenceImageUrls", List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/product.png")
            ));

            assertThat(output)
                    .containsEntry("adTone", "premium and sensory")
                    .containsEntry("attachedReferenceImageCount", 1);
            Map<String, Object> request = objectMapper.readValue(capturedRequest.get(), new TypeReference<>() {
            });
            List<Map<String, Object>> contents = listOfMaps(request.get("contents"));
            List<Map<String, Object>> parts = listOfMaps(contents.get(0).get("parts"));
            assertThat(parts).hasSize(2);
            assertThat(mapValue(parts.get(1).get("inline_data")))
                    .containsEntry("mime_type", "image/png")
                    .containsEntry("data", Base64.getEncoder().encodeToString(productImage));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void attachesManagedReferenceImageDirectlyFromObjectStorage() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "candidates": [{
                        "content": {"parts": [{"text": "{\\"status\\":\\"reference inspected\\"}"}]},
                        "finishReason": "STOP"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        try {
            CreatorProperties properties = new CreatorProperties();
            properties.getAi().setGeminiApiKey("test-key");
            properties.getAi().setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getAi().setGeminiModel("gemini-2.5-flash");
            byte[] imageBytes = new byte[]{9, 8, 7, 6};

            AssetStorageService storage = mock(AssetStorageService.class);
            when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
            when(storage.openObjectStream("creator-assets", "storyboards/shot-2.jpg"))
                    .thenReturn(new AssetStorageService.StreamedObject(
                            new ByteArrayInputStream(imageBytes),
                            "image/jpeg",
                            imageBytes.length
                    ));

            GeminiCreatorAiProvider provider = new GeminiCreatorAiProvider(
                    properties,
                    objectMapper,
                    new GeminiUsageMetadataParser(objectMapper),
                    new GoogleGenAiClientFactory(properties, WebClient.builder()),
                    new GeminiRateLimitGuard(properties),
                    WebClient.builder()
            );
            provider.setAssetStorageService(storage);

            Map<String, Object> output = provider.generate("CLIENT_REVIEW_RAG_CHAT", Map.of(
                    "renderedPrompt", "Inspect the current storyboard frame.",
                    "attachReferenceImages", true,
                    "referenceImageAssets", List.of(Map.of(
                            "bucket", "creator-assets",
                            "objectKey", "storyboards/shot-2.jpg",
                            "contentType", "image/jpeg"
                    ))
            ));

            assertThat(output)
                    .containsEntry("status", "reference inspected")
                    .containsEntry("attachedReferenceImageCount", 1);
            Map<String, Object> request = objectMapper.readValue(capturedRequest.get(), new TypeReference<>() {});
            List<Map<String, Object>> parts = listOfMaps(listOfMaps(request.get("contents")).get(0).get("parts"));
            assertThat(mapValue(parts.get(1).get("inline_data")))
                    .containsEntry("mime_type", "image/jpeg")
                    .containsEntry("data", Base64.getEncoder().encodeToString(imageBytes));
            verify(storage).openObjectStream("creator-assets", "storyboards/shot-2.jpg");
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
