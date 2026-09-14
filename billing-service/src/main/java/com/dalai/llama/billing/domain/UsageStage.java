package com.dalai.llama.billing.domain;

import java.util.Locale;

/**
 * The phase of a project a billable call belongs to, as a creator would name it.
 *
 * <p>Derived from llm-gateway's task key -- the only thing that knows what a call was for -- and
 * falling back to the model type for calls that use no prompt template (music, TTS, voice clone,
 * the video models themselves). Kept as our interpretation of the raw key rather than something
 * llm-gateway decides: the gateway knows it ran a prompt, not which phase of a production that
 * prompt belonged to, and regrouping later should not need the data regenerated.
 */
public enum UsageStage {
    IDEATION,
    PRE_PRODUCTION,
    SHOT_IMAGES,
    VIDEO_GENERATION,
    AUDIO,
    POST_PRODUCTION,
    ASSISTANT,
    OTHER;

    /** @param taskKey llm-gateway's prompt template key, null for calls that use no template
     *  @param modelType model_master.type, the fallback when there is no task key */
    public static UsageStage from(String taskKey, String modelType) {
        String key = taskKey == null ? "" : taskKey.trim().toUpperCase(Locale.ROOT);
        if (!key.isEmpty()) {
            UsageStage byKey = fromTaskKey(key);
            if (byKey != null) {
                return byKey;
            }
        }
        return fromModelType(modelType);
    }

    private static UsageStage fromTaskKey(String key) {
        // Ideas, trends and the marketing plan: everything before there is a script.
        if (key.startsWith("PROJECT_IDEA") || key.startsWith("PROJECT_REQUIREMENT")
                || key.startsWith("MARKETING_PLAN") || key.startsWith("TREND_")
                || key.equals("IDEA_CRITIQUE") || key.equals("LOCKED_IDEA_EXTRACTION")
                || key.equals("PROJECT_REFERENCE_IMAGE_ANALYSIS") || key.equals("REFERENCE_IMAGE_ANALYSIS")) {
            return IDEATION;
        }
        // The shot's own images are their own line: they are generated per shot, repeatedly, and
        // are usually where an unexpectedly large bill actually came from.
        if (key.contains("IMAGE_DESCRIBE") || key.contains("SHOT_IMAGE")) {
            return SHOT_IMAGES;
        }
        // Script, screenplay, shot list, camera/lighting plans and every critic over them.
        if (key.startsWith("PRE_PROD_") || key.startsWith("CRITIC_")) {
            return PRE_PRODUCTION;
        }
        // Building and pricing the prompt a video model runs.
        if (key.equals("MODEL_RECOMMENDATION") || key.equals("PROMPT_COMPRESSION")
                || key.equals("FOLEY_CUE_DERIVATION")) {
            return VIDEO_GENERATION;
        }
        if (key.equals("PHONEME_GUIDE") || key.equals("TRANSLATE_DIALOGUE")) {
            return AUDIO;
        }
        if (key.startsWith("CHAT")) {
            return ASSISTANT;
        }
        return null;
    }

    private static UsageStage fromModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return OTHER;
        }
        return switch (modelType.trim().toLowerCase(Locale.ROOT)) {
            case "video", "video_edit" -> VIDEO_GENERATION;
            case "music", "tts", "voice_clone", "foley", "transcription" -> AUDIO;
            case "image" -> SHOT_IMAGES;
            case "upscale", "lip_sync", "audio_video_merge" -> POST_PRODUCTION;
            default -> OTHER;
        };
    }

    /** One line saying what this stage actually paid for, so a creator reading a number on the
     * statement knows which part of their own process produced it. */
    public String description() {
        return switch (this) {
            case IDEATION -> "Finding and shaping the idea: trends, idea options, critiques and the marketing plan.";
            case PRE_PRODUCTION -> "Turning the idea into a plan: script, screenplay, shot list, camera and lighting plans.";
            case SHOT_IMAGES -> "Generating and describing the still frames each shot is built from.";
            case VIDEO_GENERATION -> "Preparing each shot for render: model choice, prompt compression and foley cues.";
            case AUDIO -> "Voice, dialogue and music: cloning, narration, translation and background tracks.";
            case POST_PRODUCTION -> "Finishing the cut: upscaling, lip-sync and muxing audio onto video.";
            case ASSISTANT -> "The in-app assistant answering and acting on your instructions.";
            case OTHER -> "Charges recorded before spend was categorised, or from a call with no category.";
        };
    }

    /** What a creator sees on the statement. */
    public String label() {
        return switch (this) {
            case IDEATION -> "Ideation";
            case PRE_PRODUCTION -> "Pre-production";
            case SHOT_IMAGES -> "Shot images";
            case VIDEO_GENERATION -> "Video generation";
            case AUDIO -> "Audio & music";
            case POST_PRODUCTION -> "Post-production";
            case ASSISTANT -> "Assistant";
            case OTHER -> "Other";
        };
    }
}
