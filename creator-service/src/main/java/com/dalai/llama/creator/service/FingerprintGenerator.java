package com.dalai.llama.creator.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's SHA-256 fingerprinting cluster - a stable hash over
 * arbitrary text parts (used for dialogue-audio reuse-vs-regenerate decisions and founder
 * embedding IDs), plus the specific dialogue-voice fingerprint and founder embedding ID
 * derivation built on top of it. Like VideoProviderCatalog and friends, needs no
 * ScreenplayVideoService collaborators - so this class takes no constructor arguments.
 */
final class FingerprintGenerator {

    String stableFingerprint(String... parts) {
        String material = String.join("|", parts == null ? new String[0] : parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }

    String dialogueFingerprint(String voiceText, Map<String, Object> inputPayload, Map<String, Object> run) {
        String material = String.join("|",
                normalizeWhitespace(voiceText),
                firstText(inputPayload == null ? null : inputPayload.get("voiceProvider"), inputPayload == null ? null : inputPayload.get("provider"), "google_chirp"),
                firstText(inputPayload == null ? null : inputPayload.get("voiceName"), inputPayload == null ? null : inputPayload.get("voice"), ""),
                firstText(inputPayload == null ? null : inputPayload.get("voiceGender"), inputPayload == null ? null : inputPayload.get("dialogueVoiceGender"), run == null ? null : firstMap(run.get("dialogueVoiceProfile")).get("voiceGender"), ""),
                firstText(inputPayload == null ? null : inputPayload.get("languageCode"), run == null ? null : run.get("languageCode"), "")
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }

    Map<String, Object> founderEmbeddingIds(String fingerprint) {
        String safe = firstText(fingerprint, stableFingerprint(UUID.randomUUID().toString()));
        String suffix = safe.substring(0, Math.min(16, safe.length()));
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("portraitEmbeddingId", "portrait-" + suffix);
        ids.put("facialFeatureEmbeddingId", "face-" + suffix);
        ids.put("voiceEmbeddingId", "voice-" + suffix);
        ids.put("identityEmbeddingVersion", "founder-kit-v1");
        return ids;
    }
}
