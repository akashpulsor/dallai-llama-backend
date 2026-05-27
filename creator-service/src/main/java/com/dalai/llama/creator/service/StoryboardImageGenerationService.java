package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryboardImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardImageGenerationService.class);
    private static final String DEFAULT_GEMINI_IMAGE_MODEL = "gemini-3.1-flash-image-preview";
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/png";
    private static final int IMAGE_RESPONSE_MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024;

    private final CreatorProperties properties;
    private final WebClient.Builder webClientBuilder;

    public StoryboardImageGenerationService(CreatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public GeneratedImage generateStoryboardImage(String prompt, String screenType) {
        String apiKey = properties.getAi().getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured for storyboard image generation.");
        }

        String model = stringValue(properties.getAi().getGeminiImageModel(), DEFAULT_GEMINI_IMAGE_MODEL);
        String imagePrompt = buildImagePrompt(prompt, screenType);
        log.info("Gemini storyboard image generation prompt model={} screenType={} prompt={}", model, screenType, imagePrompt);
        GenerateContentRequest request = buildRequest(imagePrompt);
        WebClient client = webClientBuilder
                .baseUrl(properties.getAi().getGeminiBaseUrl())
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(IMAGE_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader("x-goog-api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        byte[] bytes = requestImageBytes(client, model, request)
                .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "gemini");
        metadata.put("model", model);
        metadata.put("rawContentType", DEFAULT_IMAGE_MIME_TYPE);
        metadata.put("responsePath", "candidates[0].content.parts[0].inlineData.data");
        return new GeneratedImage(bytes == null ? new byte[0] : bytes, DEFAULT_IMAGE_MIME_TYPE, metadata);
    }

    private String buildImagePrompt(String prompt, String screenType) {
        String aspectRatio = "horizontal".equalsIgnoreCase(screenType) ? "16:9" : "9:16";
        return """
                %s

                Use aspect ratio %s.
                Return image data as inlineData in the generateContent response.
                """.formatted(stringValue(prompt, "Storyboard production image."), aspectRatio).trim();
    }

    private GenerateContentRequest buildRequest(String imagePrompt) {
        return new GenerateContentRequest(
                List.of(new Content(List.of(new Part(imagePrompt, null)))),
                new GenerationConfig(List.of("IMAGE"))
        );
    }

    private Mono<byte[]> requestImageBytes(
            WebClient client,
            String model,
            GenerateContentRequest request
    ) {
        return client
                .post()
                .uri("/models/{model}:generateContent", model)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GenerateContentResponse.class)
                .map(response -> {
                    InlineImage inlineImage = extractInlineImage(response, model);
                    return Base64.getDecoder().decode(inlineImage.base64Data());
                });
    }

    private InlineImage extractInlineImage(GenerateContentResponse response, String model) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini image provider returned no candidates for model " + model + ".");
        }

        for (Candidate candidate : response.candidates()) {
            Content content = candidate == null ? null : candidate.content();
            if (content == null || content.parts() == null || content.parts().isEmpty()) {
                continue;
            }
            for (Part part : content.parts()) {
                InlineData inlineData = part == null ? null : part.inlineData();
                if (inlineData == null || inlineData.data() == null || inlineData.data().isBlank()) {
                    continue;
                }
                return new InlineImage(
                        stringValue(inlineData.mimeType(), DEFAULT_IMAGE_MIME_TYPE),
                        inlineData.data().trim()
                );
            }
        }

        throw new IllegalStateException("Gemini image provider did not return candidates[0].content.parts[0].inlineData.data for model " + model + ".");
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private record GenerateContentRequest(
            List<Content> contents,
            GenerationConfig generationConfig
    ) {
    }

    private record GenerationConfig(
            List<String> responseModalities
    ) {
    }

    private record GenerateContentResponse(
            List<Candidate> candidates
    ) {
    }

    private record Candidate(
            Content content
    ) {
    }

    private record Content(
            List<Part> parts
    ) {
    }

    private record Part(
            String text,
            InlineData inlineData
    ) {
    }

    private record InlineData(
            String mimeType,
            String data
    ) {
    }

    private record InlineImage(
            String mimeType,
            String base64Data
    ) {
    }

    public record GeneratedImage(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata
    ) {
    }
}