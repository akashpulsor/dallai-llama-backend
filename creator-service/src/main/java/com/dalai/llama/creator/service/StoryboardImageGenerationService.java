package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.ai.GeminiCreatorAiProvider;
import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GeminiUsageMetadataParser;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
    private static final int MAX_COMPRESSOR_INPUT_CHARACTERS = 1_500_000;
    private static final int MAX_PROVIDER_REFERENCE_IMAGES = 3;
    private static final long MAX_PROVIDER_INPUT_TOKENS = 20_000L;
    private static final long FALLBACK_TOKENS_PER_REFERENCE_IMAGE = 3_000L;

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final GeminiCreatorAiProvider geminiPromptCompressor;

    public StoryboardImageGenerationService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            GeminiCreatorAiProvider geminiPromptCompressor
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.geminiPromptCompressor = geminiPromptCompressor;
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
        String providerAspectRatio = providerImageAspectRatio(aspectRatio);
        String imagePrompt = buildImagePrompt(prompt, aspectRatio);
        int originalPromptCharacters = imagePrompt.length();
        GenerateContentRequest request = buildRequest(imagePrompt, usableReferences, providerAspectRatio);
        WebClient client = googleGenAiClientFactory.client(IMAGE_RESPONSE_MAX_IN_MEMORY_BYTES);
        long originalPreflightInputTokens = countProviderInputTokens(client, model, request, imagePrompt, usableReferences.size());
        Map<String, Object> compressionMetadata = Map.of();
        boolean promptCompressed = false;
        if (originalPreflightInputTokens > MAX_PROVIDER_INPUT_TOKENS) {
            PromptCompressionResult compression = compressCompleteShotPacket(imagePrompt, originalPreflightInputTokens);
            imagePrompt = compression.compressedPrompt();
            compressionMetadata = compression.metadata();
            promptCompressed = true;
            request = buildRequest(imagePrompt, usableReferences, providerAspectRatio);
        }
        long preflightInputTokens = promptCompressed
                ? countProviderInputTokens(client, model, request, imagePrompt, usableReferences.size())
                : originalPreflightInputTokens;
        if (preflightInputTokens > MAX_PROVIDER_INPUT_TOKENS) {
            log.warn(
                    "Gemini storyboard image generation blocked before paid request model={} promptCharacters={} referenceImageCount={} inputTokens={} maximumInputTokens={}",
                    model,
                    imagePrompt.length(),
                    usableReferences.size(),
                    preflightInputTokens,
                    MAX_PROVIDER_INPUT_TOKENS
            );
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image generation was stopped before the paid image request because the complete shot packet remained over budget after semantic compression. No shot detail was silently removed."
            );
        }
        log.info(
                "Gemini storyboard image generation ready model={} backend={} baseUrl={} screenType={} promptCharacters={} referenceImageCount={} preflightInputTokens={}",
                model,
                googleGenAiClientFactory.backend(),
                googleGenAiClientFactory.baseUrl(),
                screenType,
                imagePrompt.length(),
                usableReferences.size(),
                preflightInputTokens
        );

        GenerateContentRequest providerRequest = request;
        ProviderImageResult imageResult = geminiRateLimitGuard.execute("STORYBOARD_IMAGE_GENERATE", model, () ->
                requestProviderImage(client, googleGenAiClientFactory.generateContentUri(model), model, providerRequest)
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
        tokenMetadata.put("preflightInputTokens", preflightInputTokens);
        tokenMetadata.put("outputImages", outputImages);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "gemini");
        metadata.put("model", model);
        metadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());
        metadata.put("rawContentType", inlineImage == null ? DEFAULT_IMAGE_MIME_TYPE : inlineImage.mimeType());
        metadata.put("responsePath", "first candidates[*].content.parts[*].inlineData.data");
        metadata.put("responseModalities", IMAGE_RESPONSE_MODALITIES);
        metadata.put("aspectRatio", aspectRatio);
        metadata.put("providerAspectRatio", providerAspectRatio == null ? "prompt_only" : providerAspectRatio);
        metadata.put("providerAspectRatioParameterSent", providerAspectRatio != null);
        metadata.put("referenceImageUsed", !usableReferences.isEmpty());
        metadata.put("referenceImageCount", usableReferences.size());
        metadata.put("referenceImageLimit", MAX_PROVIDER_REFERENCE_IMAGES);
        metadata.put("referenceImageRoles", usableReferences.stream().map(ReferenceImageInput::role).toList());
        metadata.put("promptCharacters", imagePrompt.length());
        metadata.put("originalPromptCharacters", originalPromptCharacters);
        metadata.put("promptSemanticallyCompressed", !compressionMetadata.isEmpty());
        if (!compressionMetadata.isEmpty()) {
            metadata.put("promptCompression", compressionMetadata);
        }
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
        String productionBrief = completeProviderPrompt(stringValue(prompt, "Storyboard production image."));
        return """
                Generate exactly one image from the production brief below.
                Return inline image data only. Do not return separate text, JSON, markdown, captions, or explanation outside the image.
                Follow the brief visual style exactly. If it asks for a storyboard sketch, preserve hand-drawn linework, planning marks, and muted color accents. Do not turn it into a black-and-white photo, grayscale render, or glossy cinematic still.

                Production brief:
                %s

                Use aspect ratio %s.
                """.formatted(productionBrief, aspectRatio).trim();
    }

    private String imageAspectRatio(String screenType) {
        return "horizontal".equalsIgnoreCase(screenType) ? "16:9" : "9:16";
    }

    private String providerImageAspectRatio(String requestedAspectRatio) {
        // Gemini image response_format uses provider enum values; keep the human ratio in the prompt/metadata.
        return null;
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
                        aspectRatio == null || aspectRatio.isBlank() ? null : new ResponseFormat(new ImageResponseFormat(aspectRatio))
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
                .limit(MAX_PROVIDER_REFERENCE_IMAGES)
                .toList();
    }

    static String completeProviderPrompt(String value) {
        String prompt = value == null ? "" : value.trim();
        if (prompt.length() > MAX_COMPRESSOR_INPUT_CHARACTERS) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "The selected shot packet is too large even for semantic compression. No prompt content was removed."
            );
        }
        return prompt;
    }

    private static final int MAX_COMPRESSION_ATTEMPTS = 2;

    private PromptCompressionResult compressCompleteShotPacket(String imagePrompt, long originalInputTokens) {
        List<String> requiredSections = requiredCompressionSections(imagePrompt);
        String priorFeedback = "";
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_COMPRESSION_ATTEMPTS; attempt++) {
            try {
                return attemptShotPacketCompression(imagePrompt, originalInputTokens, requiredSections, priorFeedback);
            } catch (ResponseStatusException ex) {
                lastFailure = ex;
                priorFeedback = ex.getReason();
                log.warn("Shot-packet compression attempt {}/{} failed, {} promptCharacters={} reason={}",
                        attempt, MAX_COMPRESSION_ATTEMPTS, attempt < MAX_COMPRESSION_ATTEMPTS ? "retrying" : "giving up",
                        imagePrompt.length(), priorFeedback);
            }
        }
        throw lastFailure;
    }

    private PromptCompressionResult attemptShotPacketCompression(
            String imagePrompt,
            long originalInputTokens,
            List<String> requiredSections,
            String priorFeedback
    ) {
        String feedbackSection = priorFeedback == null || priorFeedback.isBlank()
                ? ""
                : "\nA prior compression attempt was REJECTED for this exact reason - fix it this time: " + priorFeedback + "\n";
        String compressionPrompt = """
                You are a lossless film-production prompt compiler. Compress the COMPLETE SHOT PACKET below for an image-generation model.

                Return exactly one JSON object:
                {
                  "compressedPrompt": "complete compressed production prompt",
                  "retainedSections": ["..."],
                  "omittedFacts": []
                }

                NON-NEGOTIABLE RULES:
                - Preserve every unique production fact. Remove only duplicated wording and repeated copies of the same fact.
                - Preserve exact product identity, product/brand names, label copy, packaging geometry, colors, approved claims, and exact-vs-inspiration reference intent.
                - Preserve the selected shot's complete visual action, director intent, per-second frames, camera body/rig/movement, lens, focus, lighting, exposure, product visibility, sound/edit cues, typography/overlay copy, placement, timing, transitions, and final frame state.
                - Preserve the previous shot's outgoing boundary state and the next shot's incoming boundary state so continuity remains deterministic.
                - Preserve every client-confirmed revision and all negative constraints.
                - Do not generalize numerical values, times, percentages, names, copy, colors, settings, or continuity anchors.
                - Do not add new creative decisions. Do not return a summary. The result must remain directly executable as the complete image prompt.
                - Keep every bracketed section heading from the source in compressedPrompt so section retention can be verified mechanically - copy each heading character-for-character, do not paraphrase or reformat it.
                - omittedFacts must be an empty array. If two statements conflict, retain both and label the conflict instead of dropping either.
                %s
                COMPLETE SHOT PACKET:
                %s
                """.formatted(feedbackSection, imagePrompt).trim();
        // The compressed output for a near-cap-sized shot packet can itself run to tens of
        // thousands of tokens, and JSON-escaping inflates that further - the shared 32768 default
        // is not enough headroom. This is a mechanical, non-creative rewrite (compress without
        // losing facts), so thinking tokens are disabled to give the full budget to the answer,
        // and the model's real per-call ceiling is used instead of the shared default.
        Map<String, Object> response = geminiPromptCompressor.generate(
                "IMAGE_PROMPT_COMPRESS",
                Map.of(
                        "renderedPrompt", compressionPrompt,
                        "maxOutputTokensOverride", 65536,
                        "disableThinking", true
                )
        );
        String compressedPrompt = stringValue(response == null ? null : response.get("compressedPrompt"), "").trim();
        boolean omittedFactsDeclared = response != null && response.containsKey("omittedFacts");
        List<?> omittedFacts = response != null && response.get("omittedFacts") instanceof List<?> values
                ? values
                : List.of();
        List<String> missingSections = requiredSections.stream()
                .filter(section -> !compressedPrompt.contains(section))
                .toList();
        if (compressedPrompt.isBlank() || !omittedFactsDeclared || !omittedFacts.isEmpty() || !missingSections.isEmpty()) {
            Object finishReason = response == null ? null : response.get("finishReason");
            String reason = compressedPrompt.isBlank() ? "compressedPrompt was blank (finishReason=" + finishReason + ")"
                    : !omittedFactsDeclared ? "omittedFacts was not declared in the response"
                    : !omittedFacts.isEmpty() ? "omittedFacts was not empty: " + omittedFacts
                    : "these required section headings were missing from the compressed output (they must be copied character-for-character): " + missingSections;
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    reason
            );
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "gemini");
        metadata.put("model", response.get("model"));
        metadata.put("originalInputTokens", originalInputTokens);
        metadata.put("originalCharacters", imagePrompt.length());
        metadata.put("compressedCharacters", compressedPrompt.length());
        metadata.put("retainedSections", response.getOrDefault("retainedSections", List.of()));
        metadata.put("omittedFacts", List.of());
        metadata.put("verifiedRequiredSections", requiredSections);
        metadata.put("compressionVerified", true);
        metadata.put("tokenUsage", response.getOrDefault("tokenUsage", Map.of()));
        return new PromptCompressionResult(completeProviderPrompt(compressedPrompt), metadata);
    }

    private List<String> requiredCompressionSections(String prompt) {
        List<String> candidates = List.of(
                "[GLOBAL CONTINUITY BIBLE]",
                "[ADJACENT-SHOT EDIT BRIDGE]",
                "[COMPLETE CURRENT-SHOT DIRECTOR PACKET]",
                "[CURRENT-SHOT DEPARTMENT PLANS]",
                "[CLIENT-CONFIRMED FRAME REVISION]",
                "[CLIENT REFERENCE INTENT: USE EXACTLY]",
                "[CLIENT REFERENCE INTENT: INSPIRATION ONLY]"
        );
        return candidates.stream().filter(section -> prompt != null && prompt.contains(section)).toList();
    }

    private long countProviderInputTokens(
            WebClient client,
            String model,
            GenerateContentRequest request,
            String imagePrompt,
            int referenceImageCount
    ) {
        long fallbackTokens = pricingService.estimateTextTokens(imagePrompt)
                + (Math.max(0, referenceImageCount) * FALLBACK_TOKENS_PER_REFERENCE_IMAGE);
        try {
            CountTokensResponse response = client
                    .post()
                    .uri(googleGenAiClientFactory.countTokensUri(model))
                    .bodyValue(new CountTokensRequest(request.contents()))
                    .retrieve()
                    .bodyToMono(CountTokensResponse.class)
                    .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));
            long providerTokens = response == null ? 0 : response.totalTokens();
            return providerTokens > 0 ? providerTokens : fallbackTokens;
        } catch (RuntimeException ex) {
            log.warn(
                    "Gemini countTokens preflight unavailable model={} fallbackInputTokens={} errorType={}",
                    model,
                    fallbackTokens,
                    ex.getClass().getSimpleName()
            );
            return fallbackTokens;
        }
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

    private record CountTokensRequest(
            List<Content> contents
    ) {
    }

    private record CountTokensResponse(
            @JsonProperty("totalTokens")
            @JsonAlias("total_tokens")
            long totalTokens
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

    private record PromptCompressionResult(
            String compressedPrompt,
            Map<String, Object> metadata
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
