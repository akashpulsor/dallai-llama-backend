package com.dalai.llama.llmgateway.dto.prompt;

import java.math.BigDecimal;
import java.util.List;

/**
 * All DTOs consumed by {@code /v1/prompt/format} -- ShotContext + nested cinematography types
 * + feature flags + response shape. Consolidated into one file so a caller (video-gen) can
 * import a single class and pattern-mirrors {@code PreProductionViews}. String-typed enum
 * fields on purpose: llm-gateway shouldn't own video-gen's cinematography enum tree, and the
 * strategies only read String content anyway.
 */
public final class PromptDtos {

    private PromptDtos() {}

    /** Incoming request body for {@code POST /v1/prompt/format}. */
    public record PromptFormatRequest(String modelId, ShotContext shotContext, FeatureFlags flags) {}

    /** Response of {@code POST /v1/prompt/format} -- the composed positive/negative prompt plus
     * the resolved strategy's max prompt length so a caller's compression stage can decide
     * whether to compress and how far without a second round-trip. */
    public record PromptFormatResponse(String positive, String negative, int maxPromptLength) {}

    public record ShotContext(
            String shotRef,
            Narrative narrative,
            List<Character> characters,
            Environment environment,
            Lighting lighting,
            Camera camera,
            ProductBrand productBrand,
            Technical technical,
            List<ContinuityAnchor> continuityAnchors,
            AudioAmbience audioAmbience,
            List<DialogueBeat> dialogueBeats
    ) {}

    public record Narrative(String scriptLine, String screenplaySlug, String arcPosition) {}

    public record Character(
            String castId,
            String faceRefBucket,
            String faceRefObjectKey,
            String wardrobeNote,
            String performanceDirection,
            String voiceRefBucket,
            String voiceRefObjectKey
    ) {}

    public record Environment(String location, String timeOfDay, String weather, String environmentalEffects) {}

    public record Lighting(
            String keyLightNote,
            String mood,
            String dpLightingImageBucket,
            String dpLightingImageObjectKey
    ) {}

    public record Camera(
            String shotSize,
            String cameraNote,
            String cameraPlanImageBucket,
            String cameraPlanImageObjectKey
    ) {}

    public record ProductBrand(
            Boolean isProductHeroShot,
            String productPlacement,
            String productRefBucket,
            String productRefObjectKey
    ) {}

    public record Technical(
            Integer durationSeconds,
            String aspectRatio,
            String targetProvider,
            String targetModel,
            String voiceCloneModel,
            String editingNotes
    ) {}

    public record ContinuityAnchor(String anchorType, String subjectId, String description, String referenceObjectKey) {}

    public record AudioAmbience(
            String ambientDescription,
            String musicMoodNote,
            String backgroundMusicBucket,
            String backgroundMusicObjectKey
    ) {}

    public record DialogueBeat(
            BigDecimal startSeconds,
            BigDecimal durationSeconds,
            String text,
            String characterKey,
            String voiceReferenceUrl
    ) {}

    /** doc §19 -- closed vocabulary "ON"/"OFF" as strings so the wire format is stable across
     * services that don't share an enum. Strategies compare against {@link #ON} / {@link #OFF}. */
    public record FeatureFlags(String dialogue, String captions) {
        public static final String ON = "ON";
        public static final String OFF = "OFF";
    }
}
