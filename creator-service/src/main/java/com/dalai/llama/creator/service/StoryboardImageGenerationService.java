package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GeminiUsageMetadataParser;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class StoryboardImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardImageGenerationService.class);
    private static final String DEFAULT_GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image";
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/png";
    private static final List<String> IMAGE_RESPONSE_MODALITIES = List.of("IMAGE");
    private static final int IMAGE_RESPONSE_MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024;

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;

    public StoryboardImageGenerationService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
    }

    public GeneratedImage generateStoryboardImage(String prompt, String screenType) {
        return generateImage(prompt, null, null, screenType);
    }

    public GeneratedImage generateImageFromReference(String prompt, byte[] referenceImageBytes, String referenceContentType, String screenType) {
        if (referenceImageBytes == null || referenceImageBytes.length == 0) {
            throw new IllegalArgumentException("Reference image is required for image-conditioned generation.");
        }
        return generateImage(prompt, List.of(new ReferenceImageInput(referenceImageBytes, stringValue(referenceContentType, "image/png"), "reference")), screenType);
    }

    public GeneratedImage generateImageFromReferences(String prompt, List<ReferenceImageInput> referenceImages, String screenType) {
        List<ReferenceImageInput> usableReferences = usableReferenceImages(referenceImages);
        if (usableReferences.isEmpty()) {
            throw new IllegalArgumentException("At least one reference image is required for image-conditioned generation.");
        }
        return generateImage(prompt, usableReferences, screenType);
    }

    private GeneratedImage generateImage(String prompt, byte[] referenceImageBytes, String referenceContentType, String screenType) {
        List<ReferenceImageInput> referenceImages = referenceImageBytes == null || referenceImageBytes.length == 0
                ? List.of()
                : List.of(new ReferenceImageInput(referenceImageBytes, stringValue(referenceContentType, "image/png"), "reference"));
        return generateImage(prompt, referenceImages, screenType);
    }

    private GeneratedImage generateImage(String prompt, List<ReferenceImageInput> referenceImages, String screenType) {
        List<ReferenceImageInput> usableReferences = usableReferenceImages(referenceImages);
        String model = stringValue(properties.getAi().getGeminiImageModel(), DEFAULT_GEMINI_IMAGE_MODEL);
        String aspectRatio = imageAspectRatio(screenType);
        String imagePrompt = buildImagePrompt(prompt, aspectRatio);
        log.info("Gemini storyboard image generation prompt model={} backend={} baseUrl={} screenType={} prompt={}",
                model,
                googleGenAiClientFactory.backend(),
                googleGenAiClientFactory.baseUrl(),
                screenType,
                imagePrompt);
        GenerateContentRequest request = buildRequest(imagePrompt, usableReferences, aspectRatio);
        WebClient client = googleGenAiClientFactory.client(IMAGE_RESPONSE_MAX_IN_MEMORY_BYTES);

        ProviderImageResult imageResult = geminiRateLimitGuard.execute("STORYBOARD_IMAGE_GENERATE", model, () ->
                requestProviderImage(client, googleGenAiClientFactory.generateContentUri(model), model, request)
                        .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
        );
        InlineImage inlineImage = imageResult == null ? null : imageResult.inlineImage();
        Object usageMetadata = imageResult == null ? null : imageResult.usageMetadata();
        Map<String, Object> providerTokenUsage = usageMetadataParser.parse(usageMetadata);
        long providerInputTokens = longValue(providerTokenUsage.get("inputTokens"));
        long fallbackInputTokens = pricingService.estimateTextTokens(imagePrompt);
        long inputTokens = providerInputTokens > 0 ? providerInputTokens : fallbackInputTokens;
        String usageSource = providerInputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";
        int outputImages = Math.max(1, imageResult == null ? 0 : imageResult.outputImages());

        Map<String, Object> tokenMetadata = new LinkedHashMap<>(providerTokenUsage);
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("totalTokens", Math.max(longValue(providerTokenUsage.get("totalTokens")), inputTokens + longValue(providerTokenUsage.get("outputTokens"))));
        tokenMetadata.put("source", usageSource);
        tokenMetadata.put("fallbackInputTokens", fallbackInputTokens);
        tokenMetadata.put("outputImages", outputImages);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "gemini");
        metadata.put("model", model);
        metadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());
        metadata.put("rawContentType", inlineImage == null ? DEFAULT_IMAGE_MIME_TYPE : inlineImage.mimeType());
        metadata.put("responsePath", "first candidates[*].content.parts[*].inlineData.data");
        metadata.put("responseModalities", IMAGE_RESPONSE_MODALITIES);
        metadata.put("aspectRatio", aspectRatio);
        metadata.put("referenceImageUsed", !usableReferences.isEmpty());
        metadata.put("referenceImageCount", usableReferences.size());
        metadata.put("referenceImageRoles", usableReferences.stream().map(ReferenceImageInput::role).toList());
        metadata.put("tokenMetadata", tokenMetadata);
        metadata.put("costMetadata", pricingService.estimateGeminiImageCall(
                model,
                outputImages,
                inputTokens,
                !usableReferences.isEmpty(),
                !usableReferences.isEmpty() ? "gemini_reference_image" : "gemini_storyboard_image",
                usageSource
        ));
        return new GeneratedImage(
                inlineImage == null ? new byte[0] : decodeInlineImage(inlineImage.base64Data(), model),
                inlineImage == null ? DEFAULT_IMAGE_MIME_TYPE : inlineImage.mimeType(),
                metadata
        );
    }

    private String buildImagePrompt(String prompt, String aspectRatio) {
        return """
                Generate exactly one image from the production brief below.
                Return inline image data only. Do not return separate text, JSON, markdown, captions, or explanation outside the image.
                Follow the brief visual style exactly. If it asks for a storyboard sketch, preserve hand-drawn linework, planning marks, and muted color accents. Do not turn it into a black-and-white photo, grayscale render, or glossy cinematic still.

                Production brief:
                %s

                Use aspect ratio %s.
                """.formatted(stringValue(prompt, "Storyboard production image."), aspectRatio).trim();
    }

    private String imageAspectRatio(String screenType) {
        return "horizontal".equalsIgnoreCase(screenType) ? "16:9" : "9:16";
    }

    private GenerateContentRequest buildRequest(String imagePrompt, List<ReferenceImageInput> referenceImages, String aspectRatio) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(imagePrompt, null));
        for (ReferenceImageInput referenceImage : usableReferenceImages(referenceImages)) {
            parts.add(new Part(null, new InlineData(
                    stringValue(referenceImage.contentType(), DEFAULT_IMAGE_MIME_TYPE),
                    Base64.getEncoder().encodeToString(referenceImage.imageBytes())
            )));
        }
        return new GenerateContentRequest(
                List.of(new Content(parts)),
                new GenerationConfig(
                        IMAGE_RESPONSE_MODALITIES,
                        new ResponseFormat(new ImageResponseFormat(aspectRatio))
                )
        );
    }

    private List<ReferenceImageInput> usableReferenceImages(List<ReferenceImageInput> referenceImages) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            return List.of();
        }
        return referenceImages.stream()
                .filter(referenceImage -> referenceImage != null
                        && referenceImage.imageBytes() != null
                        && referenceImage.imageBytes().length > 0)
                .toList();
    }

    private Mono<ProviderImageResult> requestProviderImage(
            WebClient client,
            String uri,
            String model,
            GenerateContentRequest request
    ) {
        return client
                .post()
                .uri(uri)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GenerateContentResponse.class)
                .map(response -> new ProviderImageResult(
                        extractInlineImage(response, model),
                        response == null ? null : response.usageMetadata(),
                        countInlineImages(response)
                ));
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

        String diagnostics = describeImageResponse(response);
        log.warn("Gemini image provider returned no inline image for model={} diagnostics={}", model, diagnostics);
        throw new IllegalStateException("Gemini image provider returned no inline image for model " + model + ". " + diagnostics);
    }

    private byte[] decodeInlineImage(String base64Data, String model) {
        String data = stringValue(base64Data, "").trim();
        int dataUrlMarker = data.indexOf(";base64,");
        if (dataUrlMarker >= 0) {
            data = data.substring(dataUrlMarker + ";base64,".length());
        }
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Gemini image provider returned inline image data that was not valid base64 for model " + model + ".", ex);
        }
    }

    private String describeImageResponse(GenerateContentResponse response) {
        if (response == null) {
            return "response=null";
        }
        List<Candidate> candidates = response.candidates();
        if (candidates == null || candidates.isEmpty()) {
            return "candidateCount=0 usageMetadata=" + response.usageMetadata();
        }
        List<String> summaries = new ArrayList<>();
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            Candidate candidate = candidates.get(candidateIndex);
            Content content = candidate == null ? null : candidate.content();
            List<Part> parts = content == null ? null : content.parts();
            List<String> partSummaries = new ArrayList<>();
            if (parts != null) {
                for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                    Part part = parts.get(partIndex);
                    InlineData inlineData = part == null ? null : part.inlineData();
                    String text = part == null ? null : part.text();
                    if (inlineData != null) {
                        partSummaries.add("part%d:inlineData mime=%s bytes=%d".formatted(
                                partIndex,
                                stringValue(inlineData.mimeType(), DEFAULT_IMAGE_MIME_TYPE),
                                stringValue(inlineData.data(), "").length()
                        ));
                    } else if (text != null && !text.isBlank()) {
                        partSummaries.add("part%d:text=%s".formatted(partIndex, compactText(text)));
                    } else {
                        partSummaries.add("part%d:empty".formatted(partIndex));
                    }
                }
            }
            summaries.add("candidate%d finishReason=%s parts=%s".formatted(
                    candidateIndex,
                    stringValue(candidate == null ? null : candidate.finishReason(), ""),
                    partSummaries
            ));
        }
        return "candidateCount=%d %s usageMetadata=%s".formatted(candidates.size(), summaries, response.usageMetadata());
    }

    private String compactText(String text) {
        String normalized = stringValue(text, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    private int countInlineImages(GenerateContentResponse response) {
        if (response == null || response.candidates() == null) {
            return 0;
        }
        int count = 0;
        for (Candidate candidate : response.candidates()) {
            Content content = candidate == null ? null : candidate.content();
            if (content == null || content.parts() == null) {
                continue;
            }
            for (Part part : content.parts()) {
                InlineData inlineData = part == null ? null : part.inlineData();
                if (inlineData != null && inlineData.data() != null && !inlineData.data().isBlank()) {
                    count++;
                }
            }
        }
        return count;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Math.max(0, Long.parseLong(string.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerationConfig(
            List<String> responseModalities,
            ResponseFormat responseFormat
    ) {
    }

    private record ResponseFormat(
            ImageResponseFormat image
    ) {
    }

    private record ImageResponseFormat(
            String aspectRatio
    ) {
    }

    private record GenerateContentResponse(
            List<Candidate> candidates,
            Map<String, Object> usageMetadata
    ) {
    }

    private record Candidate(
            Content content,
            String finishReason
    ) {
    }

    private record Content(
            List<Part> parts
    ) {
    }

    private record Part(
            String text,
            @JsonProperty("inline_data")
            @JsonAlias("inlineData")
            InlineData inlineData
    ) {
    }

    private record InlineData(
            @JsonProperty("mime_type")
            @JsonAlias("mimeType")
            String mimeType,
            String data
    ) {
    }

    private record InlineImage(
            String mimeType,
            String base64Data
    ) {
    }

    private record ProviderImageResult(
            InlineImage inlineImage,
            Map<String, Object> usageMetadata,
            int outputImages
    ) {
    }

    public record GeneratedImage(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata
    ) {
    }

    public record ReferenceImageInput(
            byte[] imageBytes,
            String contentType,
            String role
    ) {
    }
}
