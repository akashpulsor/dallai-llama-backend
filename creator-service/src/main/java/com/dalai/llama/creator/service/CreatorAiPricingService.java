package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CreatorAiPricingService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal THIRTY_SECONDS = BigDecimal.valueOf(30);
    private static final String CURRENCY = "USD";
    private static final String PRICING_VERSION = "google-public-pricing-2026-05-31";
    private static final String GEMINI_PRICING_URL = "https://ai.google.dev/gemini-api/docs/pricing";
    private static final String VERTEX_PRICING_URL = "https://cloud.google.com/vertex-ai/docs/generative-ai/pricing";
    private static final String LUMA_PRICING_URL = "https://docs.lumalabs.ai/docs/modify-video";
    private static final String RUNWAY_PRICING_URL = "https://docs.dev.runwayml.com/guides/pricing";
    private static final String DECART_PRICING_URL = "https://docs.platform.decart.ai/getting-started/pricing";

    private final CreatorProperties properties;

    public CreatorAiPricingService(CreatorProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> pricingMatrix() {
        List<Map<String, Object>> entries = List.of(
                textEntry("gemini-2.5-flash", "Gemini JSON / planning", new BigDecimal("0.30"), new BigDecimal("2.50")),
                textEntry("gemini-2.5-flash-lite", "Gemini low-cost JSON", new BigDecimal("0.10"), new BigDecimal("0.40")),
                imageEntry(properties.getAi().getGeminiImageModel()),
                veoEntry(properties.getAi().getGeminiVideoModel(), properties.getAi().getGeminiVideoResolution(), true),
                lumaVideoEntry(properties.getAi().getLumaVideoModel()),
                runwayVideoEntry(properties.getAi().getRunwayVideoModel()),
                decartVideoEntry(properties.getAi().getDecartVideoModel()),
                lyriaEntry(properties.getAi().getLyriaMusicModel()),
                audioEnhancementEntry(),
                localWorkerEntry()
        );

        Map<String, Object> lowCostShot = new LinkedHashMap<>();
        lowCostShot.put("id", "low_cost_shot_polish");
        lowCostShot.put("label", "Low-cost shot polish");
        lowCostShot.put("description", "Gemini image preview plus local/OpenCV transform plan. CPU/OpenCV worker cost is not an AI model charge.");
        lowCostShot.put("estimatedCost", estimateGeminiImageCall(
                properties.getAi().getGeminiImageModel(),
                1,
                1800,
                true,
                "studio_polish_reference_frame"
        ));

        Map<String, Object> premiumVeo = new LinkedHashMap<>();
        premiumVeo.put("id", "premium_veo_shot");
        premiumVeo.put("label", "Premium Veo shot");
        premiumVeo.put("description", "Image-conditioned Veo video generation for a single shot.");
        premiumVeo.put("estimatedCost", estimateVeoCall(
                properties.getAi().getGeminiVideoModel(),
                decimal(properties.getAi().getGeminiVideoDurationSeconds(), BigDecimal.valueOf(8)),
                properties.getAi().getGeminiVideoResolution(),
                true,
                "premium_veo_shot"
        ));

        Map<String, Object> lyriaClip = new LinkedHashMap<>();
        lyriaClip.put("id", "lyria_music_clip");
        lyriaClip.put("label", "Lyria music / foley clip");
        lyriaClip.put("description", "Non-verbal music, ambience, foley, or SFX. Dialogue cleanup uses the separate audio-enhancement path.");
        lyriaClip.put("estimatedCost", estimateLyriaCall(
                properties.getAi().getLyriaMusicModel(),
                BigDecimal.valueOf(30),
                1,
                "generated_sound_clip"
        ));

        Map<String, Object> audioEnhancementClip = new LinkedHashMap<>();
        audioEnhancementClip.put("id", "audio_enhancement_clip");
        audioEnhancementClip.put("label", "Dialogue cleanup / voice clarity");
        audioEnhancementClip.put("description", "Preserves original voice, words, timing, and texture while removing noise and improving clarity.");
        audioEnhancementClip.put("estimatedCost", estimateAudioEnhancementCall(
                "google_audio_enhancement",
                "google-audio-enhance-preserve-v1",
                BigDecimal.valueOf(30),
                "shot_take_audio_enhancement"
        ));

        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("currency", CURRENCY);
        matrix.put("pricingVersion", PRICING_VERSION);
        matrix.put("effectiveDate", LocalDate.of(2026, 5, 31).toString());
        matrix.put("sources", List.of(GEMINI_PRICING_URL, VERTEX_PRICING_URL, LUMA_PRICING_URL, RUNWAY_PRICING_URL, DECART_PRICING_URL));
        matrix.put("note", "Provider cost estimates only. Wallet markup, taxes, storage, egress, and CPU worker costs are separate.");
        matrix.put("entries", entries);
        matrix.put("workflowEstimates", List.of(lowCostShot, premiumVeo, lyriaClip, audioEnhancementClip));
        return matrix;
    }

    public Map<String, Object> estimateTextCall(
            String provider,
            String model,
            String operation,
            long inputTokens,
            long outputTokens,
            String usageSource
    ) {
        TextRate rate = textRate(provider, model);
        BigDecimal inputCost = perMillionCost(inputTokens, rate.inputPerMillion());
        BigDecimal outputCost = perMillionCost(outputTokens, rate.outputPerMillion());
        BigDecimal total = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        usage.put("totalTokens", Math.max(0, inputTokens) + Math.max(0, outputTokens));
        usage.put("billableInputTokens", markedUpLong(inputTokens));
        usage.put("billableOutputTokens", markedUpLong(outputTokens));
        usage.put("billableTotalTokens", markedUpLong(Math.max(0, inputTokens) + Math.max(0, outputTokens)));
        usage.put("source", usageSource);

        Map<String, Object> estimate = baseEstimate(provider, model, operation, rate.sourceUrl(), "TOKEN");
        estimate.put("inputRatePerMillionTokens", rate.inputPerMillion());
        estimate.put("outputRatePerMillionTokens", rate.outputPerMillion());
        estimate.put("inputCost", inputCost);
        estimate.put("outputCost", outputCost);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", !"PROVIDER".equalsIgnoreCase(String.valueOf(usageSource)));
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateGeminiImageCall(
            String model,
            int outputImages,
            long inputTokens,
            boolean referenceImageUsed,
            String operation,
            String usageSource
    ) {
        String resolvedModel = blankToDefault(model, "gemini-2.5-flash-image");
        BigDecimal inputRate = new BigDecimal("0.30");
        BigDecimal outputImageRate = new BigDecimal("0.039");
        BigDecimal inputCost = perMillionCost(inputTokens, inputRate);
        BigDecimal outputCost = outputImageRate
                .multiply(BigDecimal.valueOf(Math.max(0, outputImages)))
                .setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("billableInputTokens", markedUpLong(inputTokens));
        usage.put("source", blankToDefault(usageSource, "ESTIMATED"));
        usage.put("outputImages", Math.max(0, outputImages));
        usage.put("billableOutputImages", markedUpLong(Math.max(0, outputImages)));
        usage.put("referenceImageUsed", referenceImageUsed);

        Map<String, Object> estimate = baseEstimate("gemini", resolvedModel, operation, GEMINI_PRICING_URL, "IMAGE");
        estimate.put("inputRatePerMillionTokens", inputRate);
        estimate.put("outputRatePerImage", outputImageRate);
        estimate.put("inputCost", inputCost);
        estimate.put("outputCost", outputCost);
        BigDecimal total = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", !"PROVIDER".equalsIgnoreCase(blankToDefault(usageSource, "ESTIMATED")));
        estimate.put("note", "Image output is priced per generated image. Input token usage uses provider usageMetadata when Gemini returns it.");
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateGeminiImageCall(
            String model,
            int outputImages,
            long inputTokens,
            boolean referenceImageUsed,
            String operation
    ) {
        return estimateGeminiImageCall(model, outputImages, inputTokens, referenceImageUsed, operation, "ESTIMATED");
    }

    public Map<String, Object> estimateVeoCall(
            String model,
            BigDecimal durationSeconds,
            String resolution,
            boolean nativeAudioRequested,
            String operation
    ) {
        String resolvedModel = blankToDefault(model, "veo-3.1-generate-preview");
        BigDecimal seconds = positive(durationSeconds, BigDecimal.valueOf(8));
        BigDecimal rate = veoSecondRate(resolvedModel, resolution, nativeAudioRequested);
        BigDecimal total = seconds.multiply(rate).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableDurationSeconds", markedUpDecimal(seconds, 3));
        usage.put("resolution", blankToDefault(resolution, "1080p"));
        usage.put("nativeAudioRequested", nativeAudioRequested);

        Map<String, Object> estimate = baseEstimate("google_veo", resolvedModel, operation, VERTEX_PRICING_URL, "SECOND");
        estimate.put("ratePerSecond", rate);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", false);
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateLumaModifyVideoCall(
            String model,
            BigDecimal durationSeconds,
            String screenType,
            String resolution,
            String operation
    ) {
        String resolvedModel = blankToDefault(model, "ray-flash-2");
        BigDecimal seconds = positive(durationSeconds, BigDecimal.valueOf(8));
        BigDecimal ratePerMillionPixels = lumaMillionPixelRate(resolvedModel);
        long pixelsPerFrame = lumaPixelsPerFrame(resolution, screenType);
        BigDecimal frames = seconds.multiply(BigDecimal.valueOf(24));
        BigDecimal millionPixels = BigDecimal.valueOf(pixelsPerFrame)
                .multiply(frames)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal total = millionPixels.multiply(ratePerMillionPixels).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableDurationSeconds", markedUpDecimal(seconds, 3));
        usage.put("estimatedFps", 24);
        usage.put("pixelsPerFrame", pixelsPerFrame);
        usage.put("estimatedMillionPixels", millionPixels.setScale(6, RoundingMode.HALF_UP));
        usage.put("screenType", blankToDefault(screenType, "vertical"));
        usage.put("resolution", blankToDefault(resolution, "720p"));
        usage.put("audioPolicy", "visual_provider_only_audio_cleanup_separate");

        Map<String, Object> estimate = baseEstimate("luma", resolvedModel, operation, LUMA_PRICING_URL, "MILLION_PIXELS");
        estimate.put("ratePerMillionPixels", ratePerMillionPixels);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", true);
        estimate.put("note", "Luma Modify Video is visual video-to-video. Audio cleanup and final mix are billed separately.");
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateRunwayVideoToVideoCall(
            String model,
            BigDecimal durationSeconds,
            String operation
    ) {
        String resolvedModel = blankToDefault(model, "aleph2");
        BigDecimal seconds = positive(durationSeconds, BigDecimal.valueOf(5));
        BigDecimal creditsPerSecond = runwayCreditsPerSecond(resolvedModel);
        BigDecimal creditUsd = new BigDecimal("0.01");
        BigDecimal totalCredits = seconds.multiply(creditsPerSecond).setScale(3, RoundingMode.HALF_UP);
        BigDecimal total = totalCredits.multiply(creditUsd).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableDurationSeconds", markedUpDecimal(seconds, 3));
        usage.put("creditsPerSecond", creditsPerSecond);
        usage.put("credits", totalCredits);
        usage.put("creditUsd", creditUsd);
        usage.put("audioPolicy", "visual_provider_only_audio_cleanup_separate");

        Map<String, Object> estimate = baseEstimate("runway", resolvedModel, operation, RUNWAY_PRICING_URL, "CREDIT");
        estimate.put("creditsPerSecond", creditsPerSecond);
        estimate.put("creditUsd", creditUsd);
        estimate.put("totalCredits", totalCredits);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", true);
        estimate.put("note", "Runway video-to-video is visual polish. Use audio enhancement/mix jobs for studio sound.");
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateDecartVirtualTryOnCall(
            String model,
            BigDecimal durationSeconds,
            String resolution,
            String operation
    ) {
        String resolvedModel = blankToDefault(model, "lucy-vton-3");
        BigDecimal seconds = positive(durationSeconds, BigDecimal.valueOf(5));
        BigDecimal rate = decartVideoSecondRate(resolvedModel, resolution);
        BigDecimal total = seconds.multiply(rate).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableDurationSeconds", markedUpDecimal(seconds, 3));
        usage.put("resolution", blankToDefault(resolution, "720p"));
        usage.put("audioPolicy", "visual_provider_only_audio_cleanup_separate");

        Map<String, Object> estimate = baseEstimate("decart", resolvedModel, operation, DECART_PRICING_URL, "SECOND");
        estimate.put("ratePerSecond", rate);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", true);
        estimate.put("note", "Decart Lucy VTON is visual wardrobe try-on. Use audio enhancement/mix jobs for studio sound.");
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateLyriaCall(
            String model,
            BigDecimal requestedDurationSeconds,
            int sampleCount,
            String operation
    ) {
        String resolvedModel = blankToDefault(model, "lyria-3-clip-preview");
        BigDecimal seconds = positive(requestedDurationSeconds, THIRTY_SECONDS);
        int clips = Math.max(1, sampleCount) * billingClipUnits(resolvedModel, seconds);
        BigDecimal rate = lyriaClipRate(resolvedModel);
        BigDecimal total = rate.multiply(BigDecimal.valueOf(clips)).setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("requestedDurationSeconds", seconds);
        usage.put("billableClipUnits", clips);
        usage.put("customerBillableClipUnits", markedUpLong(clips));
        usage.put("sampleCount", Math.max(1, sampleCount));

        Map<String, Object> estimate = baseEstimate("google_lyria", resolvedModel, operation, VERTEX_PRICING_URL, "THIRTY_SECOND_CLIP");
        estimate.put("ratePerThirtySecondClip", rate);
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", false);
        addBillingProjection(estimate, total);
        return estimate;
    }

    public Map<String, Object> estimateAudioEnhancementCall(
            String provider,
            String model,
            BigDecimal durationSeconds,
            String operation
    ) {
        String resolvedProvider = blankToDefault(provider, "google_audio_enhancement");
        String resolvedModel = blankToDefault(model, "google-audio-enhance-preserve-v1");
        BigDecimal seconds = positive(durationSeconds, THIRTY_SECONDS);
        BigDecimal ratePerMinute = properties.getAi().getBilling().getAudioEnhancementRatePerMinuteUsd() == null
                ? BigDecimal.ZERO
                : properties.getAi().getBilling().getAudioEnhancementRatePerMinuteUsd();
        BigDecimal total = seconds
                .multiply(ratePerMinute)
                .divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP)
                .setScale(6, RoundingMode.HALF_UP);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableSeconds", seconds);
        usage.put("customerBillableSeconds", markedUpDecimal(seconds, 3));
        usage.put("source", "MEDIA_ANALYSIS_OR_SHOT_DURATION");

        Map<String, Object> estimate = baseEstimate(resolvedProvider, resolvedModel, operation, "internal_configured_audio_cleanup_rate", "SECOND");
        estimate.put("ratePerMinute", ratePerMinute);
        estimate.put("ratePerSecond", ratePerMinute.divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP));
        estimate.put("totalCost", total);
        estimate.put("usage", usage);
        estimate.put("estimated", true);
        estimate.put("note", "Audio cleanup provider billing is configured internally until the provider returns authoritative usage.");
        addBillingProjection(estimate, total);
        return estimate;
    }

    public long estimateTextTokens(Object value) {
        if (value == null) {
            return 0;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(text.length() / 4.0d));
    }

    private Map<String, Object> textEntry(String model, String label, BigDecimal inputRate, BigDecimal outputRate) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", model);
        entry.put("label", label);
        entry.put("provider", "gemini");
        entry.put("model", model);
        entry.put("unit", "1M tokens");
        entry.put("inputRate", inputRate);
        entry.put("outputRate", outputRate);
        entry.put("currency", CURRENCY);
        entry.put("source", GEMINI_PRICING_URL);
        return entry;
    }

    private Map<String, Object> imageEntry(String model) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "gemini_image");
        entry.put("label", "Gemini image / Nano Banana preview");
        entry.put("provider", "gemini");
        entry.put("model", blankToDefault(model, "gemini-2.5-flash-image"));
        entry.put("unit", "image");
        entry.put("inputRatePerMillionTokens", new BigDecimal("0.30"));
        entry.put("outputRatePerImage", new BigDecimal("0.039"));
        entry.put("currency", CURRENCY);
        entry.put("source", GEMINI_PRICING_URL);
        return entry;
    }

    private Map<String, Object> veoEntry(String model, String resolution, boolean nativeAudioRequested) {
        String resolvedModel = blankToDefault(model, "veo-3.1-generate-preview");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "veo_video");
        entry.put("label", "Premium Veo enhancement");
        entry.put("provider", "google_veo");
        entry.put("model", resolvedModel);
        entry.put("unit", "second");
        entry.put("resolution", blankToDefault(resolution, "1080p"));
        entry.put("nativeAudioRequested", nativeAudioRequested);
        entry.put("ratePerSecond", veoSecondRate(resolvedModel, resolution, nativeAudioRequested));
        entry.put("currency", CURRENCY);
        entry.put("source", VERTEX_PRICING_URL);
        return entry;
    }

    private Map<String, Object> lumaVideoEntry(String model) {
        String resolvedModel = blankToDefault(model, "ray-flash-2");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "luma_modify_video");
        entry.put("label", "Luma Modify Video");
        entry.put("provider", "luma");
        entry.put("model", resolvedModel);
        entry.put("unit", "million pixels");
        entry.put("ratePerMillionPixels", lumaMillionPixelRate(resolvedModel));
        entry.put("currency", CURRENCY);
        entry.put("source", LUMA_PRICING_URL);
        entry.put("note", "Video-to-video visual edit; audio is handled by separate cleanup/mix flow.");
        return entry;
    }

    private Map<String, Object> runwayVideoEntry(String model) {
        String resolvedModel = blankToDefault(model, "aleph2");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "runway_video_to_video");
        entry.put("label", "Runway Aleph 2 video-to-video");
        entry.put("provider", "runway");
        entry.put("model", resolvedModel);
        entry.put("unit", "credit");
        entry.put("creditsPerSecond", runwayCreditsPerSecond(resolvedModel));
        entry.put("creditUsd", new BigDecimal("0.01"));
        entry.put("currency", CURRENCY);
        entry.put("source", RUNWAY_PRICING_URL);
        entry.put("note", "Video-to-video visual edit; audio is handled by separate cleanup/mix flow.");
        return entry;
    }

    private Map<String, Object> decartVideoEntry(String model) {
        String resolvedModel = blankToDefault(model, "lucy-vton-3");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "decart_virtual_try_on");
        entry.put("label", "Decart Lucy VTON try-on");
        entry.put("provider", "decart");
        entry.put("model", resolvedModel);
        entry.put("unit", "second");
        entry.put("resolution", blankToDefault(properties.getAi().getDecartVideoResolution(), "720p"));
        entry.put("ratePerSecond", decartVideoSecondRate(resolvedModel, properties.getAi().getDecartVideoResolution()));
        entry.put("currency", CURRENCY);
        entry.put("source", DECART_PRICING_URL);
        entry.put("note", "Prompt-driven wardrobe try-on for an existing creator video; audio is handled separately.");
        return entry;
    }

    private Map<String, Object> lyriaEntry(String model) {
        String resolvedModel = blankToDefault(model, "lyria-3-clip-preview");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "lyria_music");
        entry.put("label", "Lyria music / foley generation");
        entry.put("provider", "google_lyria");
        entry.put("model", resolvedModel);
        entry.put("unit", resolvedModel.toLowerCase(Locale.ROOT).contains("pro") ? "full song up to 3 mins" : "30 second clip");
        entry.put("rate", lyriaClipRate(resolvedModel));
        entry.put("currency", CURRENCY);
        entry.put("source", VERTEX_PRICING_URL);
        return entry;
    }

    private Map<String, Object> audioEnhancementEntry() {
        BigDecimal ratePerMinute = properties.getAi().getBilling().getAudioEnhancementRatePerMinuteUsd() == null
                ? BigDecimal.ZERO
                : properties.getAi().getBilling().getAudioEnhancementRatePerMinuteUsd();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "audio_enhancement");
        entry.put("label", "Audio cleanup / voice clarity");
        entry.put("provider", "google_audio_enhancement");
        entry.put("model", "google-audio-enhance-preserve-v1");
        entry.put("unit", "minute");
        entry.put("ratePerMinute", ratePerMinute);
        entry.put("ratePerSecond", ratePerMinute.divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP));
        entry.put("currency", CURRENCY);
        entry.put("source", "internal_configured_audio_cleanup_rate");
        entry.put("note", "Dialogue cleanup preserves original voice texture and uses configured provider/workflow cost.");
        return entry;
    }

    private Map<String, Object> localWorkerEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "local_opencv_worker");
        entry.put("label", "OpenCV / CPU transform");
        entry.put("provider", "local_worker");
        entry.put("model", "opencv");
        entry.put("unit", "worker task");
        entry.put("rate", BigDecimal.ZERO);
        entry.put("currency", CURRENCY);
        entry.put("source", "internal");
        entry.put("note", "No AI provider charge; infra CPU cost is tracked separately.");
        return entry;
    }

    private TextRate textRate(String provider, String model) {
        String normalizedProvider = blankToDefault(provider, "").toLowerCase(Locale.ROOT);
        String normalizedModel = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalizedProvider.contains("gemini") || normalizedModel.contains("gemini")) {
            if (normalizedModel.contains("flash-lite")) {
                return new TextRate(new BigDecimal("0.10"), new BigDecimal("0.40"), GEMINI_PRICING_URL);
            }
            return new TextRate(new BigDecimal("0.30"), new BigDecimal("2.50"), GEMINI_PRICING_URL);
        }
        BigDecimal fallbackRate = properties.getAi().getBilling().getTokenRate() == null
                ? BigDecimal.ZERO
                : properties.getAi().getBilling().getTokenRate().multiply(ONE_MILLION);
        return new TextRate(fallbackRate, fallbackRate, "creator.ai.billing.token-rate");
    }

    private BigDecimal veoSecondRate(String model, String resolution, boolean nativeAudioRequested) {
        String normalizedModel = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        String normalizedResolution = blankToDefault(resolution, "1080p").toLowerCase(Locale.ROOT);
        boolean fourK = normalizedResolution.contains("4k") || normalizedResolution.contains("2160");
        boolean fullVeo31 = normalizedModel.contains("3.1") && !normalizedModel.contains("fast") && !normalizedModel.contains("lite");
        boolean fast = normalizedModel.contains("fast");
        boolean lite = normalizedModel.contains("lite");
        boolean veo2 = normalizedModel.contains("veo-2") || normalizedModel.contains("veo2");

        if (veo2) {
            return new BigDecimal("0.50");
        }
        if (lite) {
            if (nativeAudioRequested) {
                return normalizedResolution.contains("1080") ? new BigDecimal("0.08") : new BigDecimal("0.05");
            }
            return normalizedResolution.contains("1080") ? new BigDecimal("0.05") : new BigDecimal("0.03");
        }
        if (fast) {
            if (fourK) {
                return nativeAudioRequested ? new BigDecimal("0.30") : new BigDecimal("0.25");
            }
            if (normalizedResolution.contains("1080")) {
                return nativeAudioRequested ? new BigDecimal("0.12") : new BigDecimal("0.10");
            }
            return nativeAudioRequested ? new BigDecimal("0.10") : new BigDecimal("0.08");
        }
        if (fourK && fullVeo31) {
            return nativeAudioRequested ? new BigDecimal("0.60") : new BigDecimal("0.40");
        }
        return nativeAudioRequested ? new BigDecimal("0.40") : new BigDecimal("0.20");
    }

    private BigDecimal lumaMillionPixelRate(String model) {
        String normalized = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("flash")) {
            return new BigDecimal("0.00544");
        }
        return new BigDecimal("0.01582");
    }

    private long lumaPixelsPerFrame(String resolution, String screenType) {
        String normalizedResolution = blankToDefault(resolution, "720p").toLowerCase(Locale.ROOT);
        boolean horizontal = "horizontal".equalsIgnoreCase(blankToDefault(screenType, "vertical"));
        if (normalizedResolution.contains("1080")) {
            return horizontal ? 1920L * 1080L : 1080L * 1920L;
        }
        if (normalizedResolution.contains("4k") || normalizedResolution.contains("2160")) {
            return horizontal ? 3840L * 2160L : 2160L * 3840L;
        }
        if (normalizedResolution.contains("540")) {
            return horizontal ? 960L * 540L : 540L * 960L;
        }
        return horizontal ? 1280L * 720L : 720L * 1280L;
    }

    private BigDecimal runwayCreditsPerSecond(String model) {
        String normalized = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("aleph2") || normalized.contains("aleph_2")) {
            return new BigDecimal("28");
        }
        if (normalized.contains("gen4_aleph") || normalized.contains("gen4-aleph")) {
            return new BigDecimal("15");
        }
        if (normalized.contains("gen4.5") || normalized.contains("gen4_5") || normalized.contains("gen4-5")) {
            return new BigDecimal("12");
        }
        if (normalized.contains("gen4_turbo") || normalized.contains("gen4-turbo")
                || normalized.contains("gen3a_turbo") || normalized.contains("gen3a-turbo")
                || normalized.contains("act_two") || normalized.contains("act-two")) {
            return new BigDecimal("5");
        }
        if (normalized.contains("aleph")) {
            return new BigDecimal("28");
        }
        return new BigDecimal("28");
    }

    private BigDecimal decartVideoSecondRate(String model, String resolution) {
        String normalizedModel = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalizedModel.contains("restyle")) {
            return new BigDecimal("0.01");
        }
        if (normalizedModel.contains("vton") || normalizedModel.contains("lucy")) {
            return new BigDecimal("0.04");
        }
        return new BigDecimal("0.04");
    }

    private BigDecimal lyriaClipRate(String model) {
        String normalized = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("lyria-2")) {
            return new BigDecimal("0.06");
        }
        if (normalized.contains("pro")) {
            return new BigDecimal("0.08");
        }
        return new BigDecimal("0.04");
    }

    private int billingClipUnits(String model, BigDecimal seconds) {
        String normalized = blankToDefault(model, "").toLowerCase(Locale.ROOT);
        BigDecimal divisor = normalized.contains("pro") ? BigDecimal.valueOf(180) : THIRTY_SECONDS;
        return Math.max(1, seconds.divide(divisor, 0, RoundingMode.CEILING).intValue());
    }

    private BigDecimal perMillionCost(long tokens, BigDecimal rate) {
        return BigDecimal.valueOf(Math.max(0, tokens))
                .multiply(rate == null ? BigDecimal.ZERO : rate)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private void addBillingProjection(Map<String, Object> estimate, BigDecimal actualTotalCost) {
        BigDecimal safeActual = actualTotalCost == null ? BigDecimal.ZERO : actualTotalCost;
        BigDecimal multiplier = usageMarkupMultiplier();
        BigDecimal billableTotal = safeActual.multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        estimate.put("actualTotalCost", safeActual);
        estimate.put("billableTotalCost", billableTotal);
        estimate.put("customerTotalCost", billableTotal);
        estimate.put("billingMarkupPercent", usageMarkupPercent());
        estimate.put("billingMarkupMultiplier", multiplier);
        estimate.put("billingMarkupAppliedBy", "creator-service");
    }

    private long markedUpLong(long value) {
        if (value <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(value)
                .multiply(usageMarkupMultiplier())
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    private BigDecimal markedUpDecimal(BigDecimal value, int scale) {
        BigDecimal safeValue = value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
        return safeValue.multiply(usageMarkupMultiplier()).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal usageMarkupMultiplier() {
        return BigDecimal.ONE.add(usageMarkupPercent().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal usageMarkupPercent() {
        BigDecimal percent = properties.getAi().getBilling().getUsageMarkupPercent();
        return percent == null ? BigDecimal.ZERO : percent.max(BigDecimal.ZERO);
    }

    private Map<String, Object> baseEstimate(String provider, String model, String operation, String source, String rateUnit) {
        Map<String, Object> estimate = new LinkedHashMap<>();
        estimate.put("provider", provider);
        estimate.put("model", model);
        estimate.put("operation", blankToDefault(operation, "ai_call"));
        estimate.put("currency", CURRENCY);
        estimate.put("pricingVersion", PRICING_VERSION);
        estimate.put("source", source);
        estimate.put("rateUnit", rateUnit);
        return estimate;
    }

    private BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private BigDecimal decimal(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record TextRate(BigDecimal inputPerMillion, BigDecimal outputPerMillion, String sourceUrl) {
    }
}
