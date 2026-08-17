package com.dalai.llama.creator.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's local/open-source founder-avatar model normalization
 * cluster - collapsing free-text voice/talking-avatar/lip-sync/image/video model names into the
 * canonical model identifiers this codebase's local avatar pipeline (the "dalai_llama" provider)
 * actually recognizes, plus the scene-voice-method allowlist check and pronunciation-guide text
 * substitution used alongside it. Like VideoProviderCatalog, needs no ScreenplayVideoService
 * collaborators - every method only reads its own input and MapCoercion statics - so this class
 * takes no constructor arguments.
 */
final class LocalAvatarModelNormalizer {

    private static final Set<String> SCENE_VOICE_METHODS = Set.of(
            "client_rvc_english",
            "fal_chatterbox_multilingual",
            "fal_minimax_voice_clone",
            "fal_elevenlabs_v3",
            "elevenlabs_v3_voice_clone",
            "elevenlabs_professional",
            "sarvam_voice_clone",
            "uploaded_founder_audio",
            "synthesia_managed"
    );

    String normalizeLocalVoiceModel(String value) {
        String normalized = defaultString(value, "fal_minimax_voice_clone").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.contains("client_rvc") || normalized.contains("trained_client_voice") || normalized.contains("founder_female_v1")) return "client_rvc_english";
        if (normalized.contains("chatterbox") || normalized.contains("multilingual_voice")) return "fal_chatterbox_multilingual";
        if (normalized.equals("fal_elevenlabs_v3") || normalized.equals("fal_elevenlabs")) return "fal_elevenlabs_v3";
        if (normalized.equals("elevenlabs_v3_voice_clone")
                || normalized.equals("elevenlabs_v3")
                || normalized.equals("eleven_v3")
                || normalized.equals("elevenlabs_direct")
                || normalized.equals("elevenlabs_ivc")) return "elevenlabs_v3_voice_clone";
        if (normalized.contains("sarvam")) return "sarvam_voice_clone";
        if (normalized.contains("eleven") || normalized.contains("11labs")) return "elevenlabs_professional";
        if (normalized.contains("upload") || normalized.contains("exact") || normalized.contains("founder_audio")) return "uploaded_founder_audio";
        if (normalized.contains("synthesia")) return "synthesia_managed";
        if (normalized.contains("api") || normalized.contains("fallback") || normalized.contains("proprietary")) return "api_fallback";
        if (normalized.contains("minimax") || normalized.equals("fal") || normalized.startsWith("fal_") || normalized.equals("f5") || normalized.contains("f5_tts")) return "fal_minimax_voice_clone";
        if (normalized.contains("cosy") || normalized.contains("spark") || normalized.contains("fs") || normalized.contains("xtts")) return "fal_minimax_voice_clone";
        return "fal_minimax_voice_clone";
    }

    String requireSceneVoiceMethod(String value) {
        String selected = defaultString(value, "fal_minimax_voice_clone")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (!SCENE_VOICE_METHODS.contains(selected)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported scene voice clone method: " + selected + ". Select a method from the voice dropdown."
            );
        }
        return selected;
    }

    String providerVoiceIdForMethod(String voiceModel, Map<String, Object> founderProfile) {
        Map<String, Object> profile = founderProfile == null ? Map.of() : founderProfile;
        String profileVoiceModel = firstText(firstMap(profile.get("localModels")).get("voiceModel"));
        boolean selectedProfileMethod = voiceModel.equals(profileVoiceModel);
        return switch (voiceModel) {
            case "fal_minimax_voice_clone" -> firstText(
                    profile.get("minimaxVoiceId"),
                    selectedProfileMethod ? profile.get("providerVoiceId") : null,
                    selectedProfileMethod ? profile.get("customVoiceId") : null
            );
            case "fal_elevenlabs_v3", "elevenlabs_v3_voice_clone", "elevenlabs_professional" -> firstText(
                    profile.get("elevenLabsVoiceId"),
                    selectedProfileMethod ? profile.get("providerVoiceId") : null,
                    selectedProfileMethod ? profile.get("customVoiceId") : null
            );
            case "sarvam_voice_clone" -> firstText(
                    profile.get("sarvamVoiceId"),
                    selectedProfileMethod ? profile.get("providerVoiceId") : null,
                    selectedProfileMethod ? profile.get("customVoiceId") : null
            );
            default -> "";
        };
    }

    String applyPronunciationGuide(String text, String guide) {
        String result = firstText(text);
        if (result.isBlank() || firstText(guide).isBlank()) {
            return result;
        }
        for (String line : guide.split("\\R")) {
            String clean = firstText(line);
            String separator = clean.contains("=>") ? "=>" : "=";
            int separatorIndex = clean.indexOf(separator);
            if (separatorIndex <= 0 || separatorIndex + separator.length() >= clean.length()) {
                continue;
            }
            String term = clean.substring(0, separatorIndex).trim();
            String replacement = clean.substring(separatorIndex + separator.length()).trim();
            if (term.isBlank() || replacement.isBlank()) {
                continue;
            }
            result = result.replaceAll(
                    "(?iu)\\b" + Pattern.quote(term) + "\\b",
                    Matcher.quoteReplacement(replacement)
            );
        }
        return result;
    }

    String normalizeLocalTalkingAvatarModel(String value) {
        String normalized = defaultString(value, "fal_heygen_avatar4")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("[^a-z0-9_]+", "_")
                .trim();
        if (normalized.contains("heygen")
                && (normalized.contains("avatar4") || normalized.contains("avatar_4"))) {
            return "fal_heygen_avatar4";
        }
        if (normalized.contains("happy_horse") || normalized.contains("happyhorse")) {
            return "fal_happy_horse_v1_1";
        }
        return "source_video";
    }

    String normalizeLocalLipSyncModel(String value) {
        String normalized = defaultString(value, "fal_latentsync").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.equals("avatar_native") || normalized.equals("native") || normalized.equals("none")) return "avatar_native";
        if (normalized.contains("muse")) return "fal_musetalk";
        if (normalized.contains("fal") && normalized.contains("latent")) return "fal_latentsync";
        if (normalized.contains("latent")) return "fal_latentsync";
        if (normalized.contains("sync_lab") || normalized.equals("synclabs")) return "sync_labs";
        if (normalized.contains("api") || normalized.contains("fallback") || normalized.contains("proprietary")) return "api_fallback";
        return "fal_latentsync";
    }

    String normalizeLocalImageModel(String value) {
        String normalized = defaultString(value, "gemini_storyboard").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.contains("gemini") || normalized.contains("storyboard")) return "gemini_storyboard";
        if (normalized.contains("light")) return "ic_lightning";
        return "flux_1_dev";
    }

    String normalizeLocalVideoModel(String value) {
        return "fal_seedance";
    }
}
