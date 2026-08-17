package com.dalai.llama.creator.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's provider/model catalog cluster - which video providers
 * and models exist, their capability limits (max clip seconds, compatible model names), and their
 * polling/rate-limit policy. Like CastCharacterResolver and VideoGenerationBiller, needs no
 * ScreenplayVideoService collaborators - every method here only reads env vars (via
 * MapCoercion.envString/envInt) and a couple of static defaults, so this class takes no
 * constructor arguments.
 *
 * <p>Every entry in this catalog is video-only today (mediaType "video") - ScreenplayVideoService
 * only ever calls this for scene/clip video generation. That field is included now as the seed of
 * a future DB-backed provider master table (per the plan to move this kind of per-provider config
 * out of code), not because any of these providers currently produce audio-only or multimodal
 * output in this pipeline.
 */
final class VideoProviderCatalog {

    private static final String DEFAULT_SCREENPLAY_VIDEO_PROVIDER = "gemini_omni";

    Map<String, Object> rateLimitPolicy(String provider) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if ("google_veo".equals(provider)) {
            policy.put("maxConcurrentGenerations", envInt("GOOGLE_VEO_MAX_CONCURRENT_GENERATIONS", envInt("GOOGLE_MAX_CONCURRENT_GENERATIONS", 1)));
            policy.put("pollIntervalMs", envInt("GOOGLE_VEO_VIDEO_POLL_INTERVAL_MS", envInt("GOOGLE_VIDEO_POLL_INTERVAL_MS", 15000)));
            policy.put("timeoutMs", envInt("GOOGLE_VEO_VIDEO_TIMEOUT_MS", envInt("GOOGLE_VIDEO_TIMEOUT_MS", 900000)));
        } else if ("gemini_omni".equals(provider)) {
            policy.put("maxConcurrentGenerations", envInt("GEMINI_OMNI_MAX_CONCURRENT_GENERATIONS", envInt("GOOGLE_OMNI_MAX_CONCURRENT_GENERATIONS", 1)));
            policy.put("pollIntervalMs", envInt("GEMINI_OMNI_VIDEO_POLL_INTERVAL_MS", envInt("GOOGLE_OMNI_VIDEO_POLL_INTERVAL_MS", 5000)));
            policy.put("timeoutMs", envInt("GEMINI_OMNI_VIDEO_TIMEOUT_MS", envInt("GOOGLE_OMNI_VIDEO_TIMEOUT_MS", 900000)));
        } else if ("omini".equals(provider)) {
            policy.put("maxConcurrentGenerations", envInt("OMINI_MAX_CONCURRENT_GENERATIONS", envInt("OMNI_MAX_CONCURRENT_GENERATIONS", 1)));
            policy.put("pollIntervalMs", envInt("OMINI_VIDEO_POLL_INTERVAL_MS", envInt("OMNI_VIDEO_POLL_INTERVAL_MS", 5000)));
            policy.put("timeoutMs", envInt("OMINI_VIDEO_TIMEOUT_MS", envInt("OMNI_VIDEO_TIMEOUT_MS", 900000)));
        } else if ("synthesia".equals(provider)) {
            policy.put("maxConcurrentGenerations", envInt("SYNTHESIA_MAX_CONCURRENT_GENERATIONS", 1));
            policy.put("pollIntervalMs", envInt("SYNTHESIA_VIDEO_POLL_INTERVAL_MS", 10000));
            policy.put("timeoutMs", envInt("SYNTHESIA_VIDEO_TIMEOUT_MS", 1800000));
        } else if ("dalai_llama".equals(provider)) {
            policy.put("maxConcurrentGenerations", envInt("DALAI_LLAMA_MAX_CONCURRENT_GENERATIONS", 1));
            policy.put("pollIntervalMs", envInt("DALAI_LLAMA_VIDEO_POLL_INTERVAL_MS", 5000));
            policy.put("timeoutMs", envInt("DALAI_LLAMA_VIDEO_TIMEOUT_MS", 1800000));
        } else {
            policy.put("maxConcurrentGenerations", envInt("SEEDANCE_MAX_CONCURRENT_GENERATIONS", 1));
            policy.put("pollIntervalMs", envInt("SEEDANCE_VIDEO_POLL_INTERVAL_MS", 5000));
            policy.put("timeoutMs", envInt("SEEDANCE_VIDEO_TIMEOUT_MS", 900000));
        }
        policy.put("maxClipSeconds", defaultMaxClipSecondsForProvider(provider));
        policy.put("strategy", "queue_scene_requests_and_poll_provider_operation");
        return policy;
    }

    List<Map<String, Object>> providerOptions() {
        return List.of(
                Map.of("value", "gemini_omni", "label", "Gemini Omni Flash", "maxClipSeconds", 10, "mediaType", "video"),
                Map.of("value", "seedance", "label", "DalaiLlama Video", "maxClipSeconds", 15, "mediaType", "video"),
                Map.of("value", "omini", "label", "Omini", "maxClipSeconds", 15, "mediaType", "video")
        );
    }

    List<Map<String, Object>> modelOptions(String provider) {
        if ("google_veo".equals(provider)) {
            return List.of(
                    Map.of("value", envString("GOOGLE_VEO_VIDEO_MODEL", "veo-3.1-generate-preview"), "label", "Veo 3.1"),
                    Map.of("value", "veo-3.1-fast-generate-preview", "label", "Veo 3.1 Fast")
            );
        }
        if ("gemini_omni".equals(provider)) {
            return List.of(
                    Map.of("value", envString("GEMINI_OMNI_VIDEO_MODEL", envString("GOOGLE_OMNI_VIDEO_MODEL", "gemini-omni-flash-preview")), "label", "Gemini Omni Flash")
            );
        }
        if ("omini".equals(provider)) {
            return List.of(
                    Map.of("value", "omini-video", "label", "Omini Video"),
                    Map.of("value", "omini-video-pro", "label", "Omini Video Pro")
            );
        }
        return List.of(
                Map.of("value", envString("SEEDANCE_VIDEO_MODEL", "bytedance/seedance-2.0"), "label", "Seedance 2.0"),
                Map.of("value", "bytedance/seedance-2.0/fast", "label", "Seedance 2.0 Fast"),
                // Confirmed live against fal.ai (queue.fal.run/bytedance/seedance-2.5/image-to-video -
                // required fields prompt+image_url, matching https://fal.ai/models/bytedance/seedance-2.5/image-to-video/api).
                // Single-reference-image model, structurally different from 2.0's multi-image_urls
                // continuity/cast-face array - see buildFalSeedance25Request.
                Map.of("value", "bytedance/seedance-2.5/image-to-video", "label", "Seedance 2.5 (single reference image)")
        );
    }

    String normalizeVideoProvider(String provider) {
        String normalized = defaultString(provider, DEFAULT_SCREENPLAY_VIDEO_PROVIDER)
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
        if (normalized.equals("gemini_omni")
                || normalized.equals("google_omni")
                || normalized.equals("omni_flash")
                || normalized.equals("omini_flash")
                || normalized.equals("gemini_omni_flash")
                || normalized.equals("google_omni_flash")
                || normalized.equals("gemini_omni_flash_preview")) {
            return "gemini_omni";
        }
        if (normalized.equals("omni") || normalized.equals("omini") || normalized.equals("openai_omni") || normalized.equals("openai_omini")) {
            return "omini";
        }
        if (normalized.equals("veo") || normalized.equals("google_veo") || normalized.equals("google_video") || normalized.equals("vertex_veo")) {
            return DEFAULT_SCREENPLAY_VIDEO_PROVIDER;
        }
        if (normalized.equals("seed_dance")
                || normalized.equals("byteplus_seedance")
                || normalized.equals("volcengine_seedance")
                || normalized.equals("fal_seedance")
                || normalized.equals("fal_ai_seedance")) {
            return "seedance";
        }
        if (normalized.equals("synthesia") || normalized.equals("synthesia_api") || normalized.equals("api_synthesia")) {
            return "synthesia";
        }
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("local_avatar")
                || normalized.equals("open_source")
                || normalized.equals("opensource")) {
            return "dalai_llama";
        }
        return normalized.isBlank() ? DEFAULT_SCREENPLAY_VIDEO_PROVIDER : normalized;
    }

    String modelForProvider(String provider, String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank() && isCompatibleVideoModel(provider, requestedModel)) {
            return requestedModel;
        }
        if ("omini".equals(provider)) {
            return envString("OMINI_VIDEO_MODEL", envString("OMNI_VIDEO_MODEL", "omini-video"));
        }
        if ("gemini_omni".equals(provider)) {
            return envString("GEMINI_OMNI_VIDEO_MODEL", envString("GOOGLE_OMNI_VIDEO_MODEL", "gemini-omni-flash-preview"));
        }
        if ("google_veo".equals(provider)) {
            return envString("GOOGLE_VEO_VIDEO_MODEL", envString("GOOGLE_VIDEO_MODEL", "veo-3.1-generate-preview"));
        }
        if ("synthesia".equals(provider)) {
            return envString("SYNTHESIA_VIDEO_MODEL", "synthesia-avatar-video");
        }
        if ("dalai_llama".equals(provider)) {
            return envString("DALAI_LLAMA_VIDEO_MODEL", "source_video");
        }
        return envString("SEEDANCE_VIDEO_MODEL", "bytedance/seedance-2.0");
    }

    private boolean isCompatibleVideoModel(String provider, String model) {
        String normalizedProvider = normalizeVideoProvider(provider);
        String normalizedModel = defaultString(model, "").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalizedModel.isBlank()) {
            return false;
        }
        if ("gemini_omni".equals(normalizedProvider)) {
            return normalizedModel.contains("gemini") || normalizedModel.contains("omni");
        }
        if ("google_veo".equals(normalizedProvider)) {
            return normalizedModel.contains("veo");
        }
        if ("omini".equals(normalizedProvider)) {
            return normalizedModel.contains("omini") || normalizedModel.contains("omni");
        }
        if ("seedance".equals(normalizedProvider)) {
            return normalizedModel.contains("seedance") || normalizedModel.contains("seed_dance");
        }
        if ("synthesia".equals(normalizedProvider)) {
            return normalizedModel.contains("synthesia") || normalizedModel.contains("avatar") || normalizedModel.contains("digital");
        }
        if ("dalai_llama".equals(normalizedProvider)) {
            return normalizedModel.contains("liveportrait")
                    || normalizedModel.contains("ltx")
                    || normalizedModel.contains("flux")
                    || normalizedModel.contains("ic_light")
                    || normalizedModel.contains("echo")
                    || normalizedModel.contains("face")
                    || normalizedModel.contains("avatar")
                    || normalizedModel.contains("local");
        }
        return true;
    }

    int modelCapabilityMaxClipSeconds(String provider, String model, Object requestedMaxClipSeconds) {
        String normalizedProvider = normalizeVideoProvider(provider);
        int providerMax = defaultMaxClipSecondsForProvider(normalizedProvider, model);
        int requested = intValue(requestedMaxClipSeconds, providerMax);
        if (!"google_veo".equals(normalizedProvider) && requested >= 20) {
            providerMax = Math.max(providerMax, Math.min(requested, 20));
        }
        return clampInt(requested, 1, providerMax);
    }

    int defaultMaxClipSecondsForProvider(String provider) {
        return defaultMaxClipSecondsForProvider(provider, "");
    }

    int defaultMaxClipSecondsForProvider(String provider, String model) {
        String normalizedModel = defaultString(model, "").toLowerCase(Locale.ROOT).replace('-', '_');
        if ("google_veo".equals(provider)) {
            return clampInt(envInt("GOOGLE_VEO_MAX_CLIP_SECONDS", envInt("GOOGLE_MAX_CLIP_SECONDS", 8)), 1, 8);
        }
        if (normalizedModel.contains("20") || normalizedModel.contains("twenty") || normalizedModel.contains("long")) {
            return 20;
        }
        if ("gemini_omni".equals(provider)) {
            return clampInt(envInt("GEMINI_OMNI_MAX_CLIP_SECONDS", envInt("GOOGLE_OMNI_MAX_CLIP_SECONDS", 10)), 1, 10);
        }
        if ("omini".equals(provider)) {
            return clampInt(envInt("OMINI_MAX_CLIP_SECONDS", envInt("OMNI_MAX_CLIP_SECONDS", 15)), 1, 15);
        }
        if ("synthesia".equals(provider)) {
            return clampInt(envInt("SYNTHESIA_MAX_CLIP_SECONDS", 20), 1, 60);
        }
        if ("dalai_llama".equals(provider)) {
            return clampInt(envInt("DALAI_LLAMA_MAX_CLIP_SECONDS", 8), 1, 20);
        }
        return clampInt(envInt("SEEDANCE_MAX_CLIP_SECONDS", 15), 1, 15);
    }

    String googleVeoBaseUrl(String model) {
        String explicit = envString("GOOGLE_VEO_BASE_URL", envString("GOOGLE_BASE_URL", ""));
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (!googleVeoUsesVertex()) {
            return "https://generativelanguage.googleapis.com/v1beta";
        }
        String projectId = envString("GOOGLE_VEO_PROJECT_ID", envString("GOOGLE_CLOUD_PROJECT_ID", envString("GOOGLE_CLOUD_PROJECT", "")));
        String location = envString("GOOGLE_VEO_LOCATION", envString("GOOGLE_CLOUD_LOCATION", "us-central1"));
        if (projectId.isBlank()) {
            return "";
        }
        return "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s"
                .formatted(location, projectId, location, model);
    }

    private boolean googleVeoUsesVertex() {
        String backend = envString("GOOGLE_VEO_BACKEND", envString("CREATOR_GOOGLE_GENAI_BACKEND", envString("GOOGLE_GENAI_BACKEND", "ai_studio")))
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        return backend.equals("vertex") || backend.equals("vertex_ai");
    }

    String providerLabel(String provider) {
        if ("google_veo".equals(provider)) {
            return "Google Veo";
        }
        if ("gemini_omni".equals(provider)) {
            return "Gemini Omni Flash";
        }
        if ("synthesia".equals(provider)) {
            return "Synthesia";
        }
        if ("dalai_llama".equals(provider)) {
            return "Dalai Llama local";
        }
        return "omini".equals(provider) ? "Omini" : "Seedance";
    }
}
