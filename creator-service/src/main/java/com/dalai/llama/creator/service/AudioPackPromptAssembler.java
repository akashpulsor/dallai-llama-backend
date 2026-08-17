package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's audio-pack prompt-assembly cluster - resolving the
 * dialogue/voiceover text to speak (audioPackVoiceText/srtCueText), normalizing the requested
 * audio job type (applyAudioRequestType/normalizeAudioRequestType/normalizeMusicSource), and
 * building the background-music generation prompt (audioPackMusicPrompt/
 * backgroundMusicGenerationPrompt/tempoForPace/backgroundMusicSceneStructure/
 * backgroundMusicCharacterContext). All nine methods only call each other and MapCoercion
 * statics - no ScreenplayVideoService collaborators (repositories, services, logger) - so, like
 * VideoProviderCatalog and friends, this class takes no constructor arguments. Every one of these
 * methods was already private with zero external (owner.) callers before this move.
 */
final class AudioPackPromptAssembler {

    String audioPackVoiceText(Map<String, Object> inputPayload, Map<String, Object> run, CreatorScript script) {
        String requested = firstText(
                inputPayload == null ? null : inputPayload.get("voiceText"),
                inputPayload == null ? null : inputPayload.get("dialogueText"),
                inputPayload == null ? null : inputPayload.get("script"),
                inputPayload == null ? null : inputPayload.get("voiceoverScript")
        );
        if (!requested.isBlank()) {
            return truncate(requested, 4800);
        }
        Map<String, Object> audioProductionPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("audioProductionPlan"),
                run == null ? null : run.get("audioProductionPlan")
        );
        Map<String, Object> dialoguePlan = firstMap(
                audioProductionPlan.get("dialoguePlan"),
                audioProductionPlan.get("voiceDialogue"),
                run == null ? null : run.get("dialoguePlan")
        );
        String planned = firstText(
                dialoguePlan.get("voiceoverScript"),
                dialoguePlan.get("dialogueScript"),
                dialoguePlan.get("script"),
                dialoguePlan.get("prompt"),
                run == null ? null : run.get("voiceoverScript"),
                run == null ? null : run.get("dialogueScript")
        );
        if (!planned.isBlank() && !planned.equalsIgnoreCase("auto")) {
            return truncate(planned, 4800);
        }
        String cueText = srtCueText(firstList(
                run == null ? null : run.get("srtCues"),
                firstMap(run == null ? null : run.get("srtFile")).get("cues"),
                firstMap(inputPayload == null ? null : inputPayload.get("srtFile")).get("cues")
        ));
        if (!cueText.isBlank()) {
            return truncate(cueText, 4800);
        }
        StringBuilder scenesText = new StringBuilder();
        for (Map<String, Object> scene : mapListValue(run == null ? null : run.get("scenes"))) {
            String line = firstText(
                    scene.get("voiceover"),
                    scene.get("dialogue"),
                    scene.get("spokenLine"),
                    scene.get("caption"),
                    scene.get("textOverlay")
            );
            if (!line.isBlank()) {
                if (!scenesText.isEmpty()) {
                    scenesText.append(System.lineSeparator());
                }
                scenesText.append(line);
            }
        }
        if (!scenesText.isEmpty()) {
            return truncate(scenesText.toString(), 4800);
        }
        return truncate(defaultString(script == null ? null : script.getScriptText(), ""), 4800);
    }

    private String srtCueText(List<Object> cues) {
        StringBuilder builder = new StringBuilder();
        for (Object cue : cues == null ? List.of() : cues) {
            Map<String, Object> cueMap = mapValue(cue);
            String line = firstText(cueMap.get("text"), cueMap.get("caption"), cueMap.get("dialogue"), cueMap.get("line"));
            if (!line.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    String audioPackMusicPrompt(
            Map<String, Object> inputPayload,
            Map<String, Object> audioProductionPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> run
    ) {
        Map<String, Object> musicPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("musicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("musicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("music"),
                soundDesignPlan == null ? null : soundDesignPlan.get("backgroundMusic"),
                run == null ? null : run.get("musicPlan")
        );
        Map<String, Object> freeMusicPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("freeMusicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("freeMusicPlan"),
                soundDesignPlan == null ? null : soundDesignPlan.get("freeMusicPlan"),
                run == null ? null : run.get("freeMusicPlan")
        );
        String prompt = firstText(
                inputPayload == null ? null : inputPayload.get("musicPrompt"),
                inputPayload == null ? null : inputPayload.get("backgroundMusicPrompt"),
                musicPlan.get("prompt"),
                musicPlan.get("brief"),
                freeMusicPlan.get("searchQuery"),
                freeMusicPlan.get("fallbackQuery"),
                run == null ? null : run.get("backgroundMusicPrompt")
        );
        if (!prompt.isBlank() && !prompt.equalsIgnoreCase("auto")) {
            return backgroundMusicGenerationPrompt(prompt, inputPayload, audioProductionPlan, soundDesignPlan, run);
        }
        Map<String, Object> pacingProfile = firstMap(run == null ? null : run.get("videoPacingProfile"));
        String pace = firstText(pacingProfile.get("paceKey"), pacingProfile.get("pace"), pacingProfile.get("style"), "medium paced");
        String title = firstText(run == null ? null : run.get("title"), "product commercial");
        return backgroundMusicGenerationPrompt(
                "Royalty-safe instrumental background music for a %s short-form ad. Pace: %s.".formatted(title, pace),
                inputPayload,
                audioProductionPlan,
                soundDesignPlan,
                run
        );
    }

    void applyAudioRequestType(Map<String, Object> inputPayload) {
        if (inputPayload == null) {
            return;
        }
        String requestType = normalizeAudioRequestType(firstText(
                inputPayload.get("audioRequestType"),
                inputPayload.get("audio_request_type"),
                inputPayload.get("requestType"),
                inputPayload.get("request_type")
        ));
        if (requestType.isBlank()) {
            return;
        }
        inputPayload.put("audioRequestType", requestType);
        if ("dialogue".equals(requestType)) {
            inputPayload.put("generateDialogue", true);
            inputPayload.put("generateVoice", true);
            inputPayload.put("musicSource", "none");
            inputPayload.put("backgroundMusicSource", "none");
            inputPayload.put("generateMusic", false);
            inputPayload.put("generateAiMusic", false);
            return;
        }
        if ("background_music".equals(requestType)) {
            inputPayload.put("generateDialogue", false);
            inputPayload.put("generateVoice", false);
            String musicSource = normalizeMusicSource(firstText(
                    inputPayload.get("musicSource"),
                    inputPayload.get("backgroundMusicSource"),
                    "free_licensed"
            ));
            inputPayload.put("musicSource", musicSource);
            inputPayload.put("backgroundMusicSource", musicSource);
            boolean aiMusic = "ai_generated".equals(musicSource);
            inputPayload.put("generateMusic", aiMusic);
            inputPayload.put("generateAiMusic", aiMusic);
        }
    }

    private String normalizeAudioRequestType(String value) {
        String normalized = defaultString(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.equals("dialogue") || normalized.equals("voice") || normalized.equals("voiceover")
                || normalized.equals("dialogue_voice") || normalized.equals("dialogue_voiceover")) {
            return "dialogue";
        }
        if (normalized.equals("music") || normalized.equals("background_music") || normalized.equals("bgm")
                || normalized.equals("music_bed") || normalized.equals("background_music_bed")) {
            return "background_music";
        }
        return "";
    }

    private String backgroundMusicGenerationPrompt(
            String creativeDirection,
            Map<String, Object> inputPayload,
            Map<String, Object> audioProductionPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> run
    ) {
        Map<String, Object> pacingProfile = firstMap(run == null ? null : run.get("videoPacingProfile"));
        Map<String, Object> finishingPlan = firstMap(inputPayload == null ? null : inputPayload.get("videoFinishingPlan"), run == null ? null : run.get("videoFinishingPlan"));
        Map<String, Object> musicPlan = firstMap(
                audioProductionPlan == null ? null : audioProductionPlan.get("music"),
                soundDesignPlan == null ? null : soundDesignPlan.get("backgroundMusic"),
                inputPayload == null ? null : inputPayload.get("musicPlan")
        );
        String title = firstText(
                run == null ? null : run.get("title"),
                firstMap(run == null ? null : run.get("screenplayJson")).get("projectTitle"),
                "short-form commercial"
        );
        String category = firstText(
                run == null ? null : run.get("category"),
                run == null ? null : run.get("topicType"),
                firstMap(run == null ? null : run.get("productUnderstanding")).get("productCategory"),
                "advertisement"
        );
        String pace = firstText(
                pacingProfile.get("paceKey"),
                pacingProfile.get("pace"),
                pacingProfile.get("style"),
                finishingPlan.get("pacing"),
                "medium paced"
        );
        String tempo = firstText(
                musicPlan.get("tempo"),
                pacingProfile.get("musicTempo"),
                pacingProfile.get("tempo"),
                tempoForPace(pace)
        );
        String mood = firstText(
                musicPlan.get("mood"),
                finishingPlan.get("mood"),
                pacingProfile.get("mood"),
                soundDesignPlan == null ? null : soundDesignPlan.get("mood"),
                "confident, cinematic, modern"
        );
        int durationSeconds = positiveInt(firstValue(
                inputPayload == null ? null : inputPayload.get("durationSeconds"),
                inputPayload == null ? null : inputPayload.get("targetDurationSeconds"),
                run == null ? null : run.get("durationSeconds")
        ), 60);
        String structure = backgroundMusicSceneStructure(run);
        String characterContext = backgroundMusicCharacterContext(run);
        String ambiencePrompt = firstText(
                firstMap(soundDesignPlan == null ? null : soundDesignPlan.get("ambience")).get("prompt"),
                audioProductionPlan == null ? null : firstMap(audioProductionPlan.get("ambience")).get("prompt"),
                finishingPlan.get("ambiencePrompt"),
                "subtle room tone that matches each scene"
        );
        String sfxPrompt = firstText(
                firstMap(soundDesignPlan == null ? null : soundDesignPlan.get("soundEffects")).get("prompt"),
                audioProductionPlan == null ? null : firstMap(audioProductionPlan.get("soundEffects")).get("prompt"),
                finishingPlan.get("soundFxPrompt"),
                "small whooshes, clicks, and transition accents only where useful"
        );
        return truncate("""
                Create original instrumental background music for this finished video.

                Video: %s
                Category: %s
                Creative direction: %s
                Mood: %s
                Tempo: %s
                Target length: %d seconds

                Cast context for emotional scoring: %s
                Use the stated character gender, role, and age only to shape instrumentation, energy, and emotional framing. Keep the output instrumental and do not infer or generate a voice from this context.

                Timeline structure:
                %s

                Mix and sound design requirements:
                - No vocals, no lyrics, no spoken dialogue, no narration.
                - Dialogue is primary. Leave the vocal midrange clear and keep music ready to duck under speech.
                - Keep the bed loopable and easy to trim for a 60 second vertical ad.
                - Keep ambience subtle: %s.
                - Use sound effects sparingly: %s.
                - Match reverb to the scene spaces and camera distance.
                - Build smooth fades between scenes and avoid abrupt endings.
                - Make the melody original and royalty-safe; do not imitate famous songs, artists, jingles, or copyrighted melodies.
                """.formatted(
                title,
                category,
                firstText(creativeDirection, "modern commercial background score"),
                mood,
                tempo,
                Math.max(4, Math.min(90, durationSeconds)),
                characterContext,
                structure,
                ambiencePrompt,
                sfxPrompt
        ).trim(), 2200);
    }

    private String tempoForPace(String pace) {
        String normalized = defaultString(pace, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("fast") || normalized.contains("rapid") || normalized.contains("high")) {
            return "120-145 BPM, energetic but not busy";
        }
        if (normalized.contains("slow") || normalized.contains("luxury") || normalized.contains("calm")) {
            return "70-95 BPM, spacious and premium";
        }
        return "95-120 BPM, steady commercial pulse";
    }

    private String backgroundMusicSceneStructure(Map<String, Object> run) {
        List<Map<String, Object>> scenes = mapListValue(run == null ? null : run.get("scenes"));
        if (scenes.isEmpty()) {
            return "- Start with a clear hook, build through the proof section, and resolve cleanly under the CTA.";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(8, scenes.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> scene = scenes.get(index);
            String time = firstText(scene.get("timeline"), scene.get("timeRange"), scene.get("time_range"), scene.get("time"), "Scene " + (index + 1));
            String title = firstText(scene.get("title"), scene.get("hook"), scene.get("name"), "Scene " + (index + 1));
            String action = firstText(scene.get("action"), scene.get("visual"), scene.get("description"), scene.get("camera"), scene.get("shotDescription"));
            builder.append("- ")
                    .append(time)
                    .append(": ")
                    .append(title);
            if (!action.isBlank()) {
                builder.append(" - ").append(truncate(normalizeWhitespace(action), 160));
            }
            builder.append(System.lineSeparator());
        }
        if (scenes.size() > limit) {
            builder.append("- Continue the same motif through remaining scenes, lifting gently into the CTA.");
        }
        return builder.toString().trim();
    }

    private String backgroundMusicCharacterContext(Map<String, Object> run) {
        Map<String, Object> screenplay = firstMap(
                run == null ? null : run.get("screenplayJson"),
                run == null ? null : run.get("scriptJson")
        );
        List<Map<String, Object>> characters = mapListValue(firstValue(
                run == null ? null : run.get("storyCharacters"),
                run == null ? null : run.get("characters"),
                screenplay.get("storyCharacters"),
                screenplay.get("characters")
        ));
        if (characters.isEmpty()) {
            return "No cast gender was supplied. Keep the score inclusive, neutral, and led by the story beat.";
        }

        List<String> descriptions = new ArrayList<>();
        for (Map<String, Object> character : characters) {
            String name = firstText(character.get("name"), character.get("characterName"), character.get("id"), "Character");
            String gender = firstText(character.get("gender"), character.get("genderIdentity"), "unspecified");
            String role = firstText(character.get("characterRole"), character.get("role"), character.get("roleInShort"), "on-screen lead");
            String age = firstText(character.get("age"), character.get("ageRange"), character.get("ageGroup"));
            descriptions.add("%s (%s, %s%s)".formatted(
                    name,
                    gender,
                    role,
                    age.isBlank() ? "" : ", " + age
            ));
        }
        return truncate(String.join("; ", descriptions), 600);
    }

    String normalizeMusicSource(String value) {
        String normalized = defaultString(value, "free_licensed")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank() || "auto".equals(normalized) || "royalty_free".equals(normalized)
                || "copyright_free".equals(normalized) || "non_copyright".equals(normalized)
                || "non_copyrighted".equals(normalized) || "free".equals(normalized)
                || "free_music".equals(normalized) || "free_licensed_music".equals(normalized)) {
            return "free_licensed";
        }
        if ("ai".equals(normalized) || "ai_music".equals(normalized) || "generated".equals(normalized)
                || "ai_generated".equals(normalized) || "google_lyria".equals(normalized) || "lyria".equals(normalized)) {
            return "ai_generated";
        }
        if ("none".equals(normalized) || "no_music".equals(normalized) || "off".equals(normalized)) {
            return "none";
        }
        return "free_licensed";
    }

    Map<String, Object> withAudioMixStandards(Map<String, Object> plan) {
        Map<String, Object> normalized = new LinkedHashMap<>(plan == null ? Map.of() : plan);
        normalized.put("audioMixStandards", audioMixStandards(normalized.get("audioMixStandards"), normalized.get("audio_mix_standards")));
        normalized.put("audioProductionPolicy", "dialogue_first_music_ducked_room_tone_sparse_sfx_scene_reverb_smooth_fades");
        return normalized;
    }

    Map<String, Object> audioMixStandards(Object... overrides) {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("dialogueLevel", "consistent_speech_first");
        standards.put("backgroundMusicDucking", "duck_under_speech");
        standards.put("ambientRoomTone", "maintain_low_scene_matched_room_tone");
        standards.put("soundEffectsUse", "small_sfx_sparingly_for_whooshes_clicks_transitions");
        standards.put("reverbMatch", "match_scene_space_and_camera_distance");
        standards.put("fades", "smooth_fades_between_audio_segments");
        standards.put("dialogueTargetDb", -3);
        standards.put("musicBedDb", -18);
        standards.put("ambienceBedDb", -22);
        standards.put("sfxPeakDb", -9);
        standards.put("fadeMs", 120);
        if (overrides != null) {
            for (Object override : overrides) {
                Map<String, Object> custom = mapValue(override);
                if (!custom.isEmpty()) {
                    standards.putAll(custom);
                }
            }
        }
        return standards;
    }
}
