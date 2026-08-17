package com.dalai.llama.creator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's dialogue-voice-profile selection cluster - picking the
 * best-scoring cast/character candidate across every source the request/run/script payloads might
 * carry (cast mappings, available actors, story characters), then deriving a voice gender and
 * speaker name from it. Like VideoProviderCatalog and friends, needs no ScreenplayVideoService
 * collaborators - every method only reads its input and MapCoercion statics - so this class takes
 * no constructor arguments.
 */
final class VoiceCandidateSelector {

    String voiceProviderFrom(Object value) {
        String normalized = stringValue(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("open_source")) {
            return "dalai_llama";
        }
        if (normalized.equals("elevenlabs") || normalized.equals("eleven_labs")) {
            return "elevenlabs";
        }
        if (normalized.equals("google")
                || normalized.equals("google_chirp")
                || normalized.equals("chirp")
                || normalized.equals("gemini_tts")
                || normalized.equals("gemini")) {
            return "google_chirp";
        }
        return "";
    }

    Map<String, Object> dialogueVoiceProfile(
            Map<String, Object> inputPayload,
            Map<String, Object> run,
            Map<String, Object> scriptPayload
    ) {
        Map<String, Object> requestedProfile = firstMap(
                inputPayload == null ? null : inputPayload.get("dialogueVoiceProfile"),
                inputPayload == null ? null : inputPayload.get("voiceProfile"),
                run == null ? null : run.get("dialogueVoiceProfile"),
                scriptPayload == null ? null : scriptPayload.get("dialogueVoiceProfile")
        );
        String requestedGender = normalizeVoiceGender(firstText(
                inputPayload == null ? null : inputPayload.get("voiceGender"),
                inputPayload == null ? null : inputPayload.get("dialogueVoiceGender"),
                requestedProfile.get("voiceGender"),
                requestedProfile.get("gender")
        ));
        String requestedSpeaker = firstText(
                inputPayload == null ? null : inputPayload.get("speakerName"),
                inputPayload == null ? null : inputPayload.get("dialogueSpeakerName"),
                requestedProfile.get("speakerName"),
                requestedProfile.get("name")
        );

        List<Map<String, Object>> candidates = new ArrayList<>();
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("characterCastMappings"));
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("castMappings"));
        addVoiceCandidates(candidates, run == null ? null : run.get("characterCastMappings"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("characterCastMappings"));
        addVoiceCandidates(candidates, firstMap(scriptPayload == null ? null : scriptPayload.get("creatorContext")).get("characterCastMappings"));
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("availableActors"));
        addVoiceCandidates(candidates, run == null ? null : run.get("availableActors"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("availableActors"));
        addVoiceCandidates(candidates, run == null ? null : run.get("storyCharacters"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("storyCharacters"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("characters"));

        Map<String, Object> lead = candidates.stream()
                .max((left, right) -> Integer.compare(voiceCandidateScore(left), voiceCandidateScore(right)))
                .orElseGet(LinkedHashMap::new);
        Map<String, Object> cast = firstMap(lead.get("castPayload"), lead.get("actor"), lead.get("cast"), lead);
        Map<String, Object> character = firstMap(lead.get("characterPayload"), lead.get("character"), lead);
        String gender = firstText(requestedGender, normalizeVoiceGender(firstText(
                cast.get("gender"),
                cast.get("genderIdentity"),
                character.get("gender"),
                character.get("genderIdentity"),
                lead.get("gender"),
                lead.get("genderIdentity")
        )));
        String speakerName = firstText(
                requestedSpeaker,
                cast.get("name"),
                cast.get("displayName"),
                character.get("name"),
                character.get("characterName"),
                lead.get("castDisplayName"),
                lead.get("characterName")
        );
        Map<String, Object> profile = new LinkedHashMap<>();
        if (!gender.isBlank()) {
            profile.put("voiceGender", gender);
        }
        if (!speakerName.isBlank()) {
            profile.put("speakerName", speakerName);
        }
        profile.put("selectionSource", requestedGender.isBlank() ? (lead.isEmpty() ? "configured_default" : "cast_mapping") : "user_selected_cast");
        return profile;
    }

    private void addVoiceCandidates(List<Map<String, Object>> candidates, Object value) {
        if (candidates != null) {
            candidates.addAll(mapListValue(value));
        }
    }

    private int voiceCandidateScore(Map<String, Object> candidate) {
        Map<String, Object> cast = firstMap(candidate == null ? null : candidate.get("castPayload"), candidate == null ? null : candidate.get("actor"), candidate == null ? null : candidate.get("cast"), candidate);
        String role = firstText(
                candidate == null ? null : candidate.get("characterRole"),
                candidate == null ? null : candidate.get("roleInShort"),
                candidate == null ? null : candidate.get("role"),
                cast.get("roleInShort"),
                cast.get("role")
        ).toLowerCase(Locale.ROOT);
        int score = 0;
        if (role.contains("main")) score += 30;
        if (role.contains("lead") || role.contains("primary") || role.contains("narrator")) score += 20;
        if (!normalizeVoiceGender(firstText(cast.get("gender"), candidate == null ? null : candidate.get("gender"))).isBlank()) score += 10;
        if (!firstText(cast.get("name"), candidate == null ? null : candidate.get("characterName")).isBlank()) score += 1;
        return score;
    }

    private String normalizeVoiceGender(String value) {
        String normalized = defaultString(value, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        if (normalized.startsWith("female") || "woman".equals(normalized) || "girl".equals(normalized)) {
            return "female";
        }
        if (normalized.startsWith("male") || "man".equals(normalized) || "boy".equals(normalized)) {
            return "male";
        }
        return "";
    }
}
