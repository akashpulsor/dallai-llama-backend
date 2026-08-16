package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.repository.CreatorAvatarSceneDialogueRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.dalai.llama.creator.service.screenplayvideo.AvatarDialogueSyncGatewayImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenplayVideoServiceAvatarScriptTest {

    @Test
    @SuppressWarnings("unchecked")
    void splitsAvatarScriptWithoutRepeatingFullDialogueInEveryClip() throws Exception {
        ScreenplayVideoService service = service();
        String script = """
                Procrastination is often an emotional protection response, not a lack of willpower.
                When a task feels uncertain or threatening, the nervous system searches for immediate relief.
                Naming the emotion and choosing one small action can make starting feel safer and more possible.
                """.replaceAll("\\s+", " ").trim();

        Method avatarScriptScenes = ScreenplayVideoService.class.getDeclaredMethod(
                "avatarScriptScenes",
                List.class,
                String.class
        );
        avatarScriptScenes.setAccessible(true);
        List<Map<String, Object>> sourceScenes = (List<Map<String, Object>>) avatarScriptScenes.invoke(
                service,
                List.of(Map.of("providerPrompt", "Keep the same creator framing.")),
                script
        );

        Method splitScenes = ScreenplayVideoService.class.getDeclaredMethod(
                "splitScenesForModelCapability",
                List.class,
                int.class
        );
        splitScenes.setAccessible(true);
        List<Map<String, Object>> clips = (List<Map<String, Object>>) splitScenes.invoke(service, sourceScenes, 8);

        assertTrue(clips.size() > 1);
        assertEquals(
                script,
                clips.stream().map(clip -> String.valueOf(clip.get("dialogueScript"))).reduce((left, right) -> left + " " + right).orElse("")
        );
        clips.forEach(clip -> {
            assertEquals(clip.get("dialogueScript"), clip.get("spokenText"));
            assertEquals(clip.get("dialogueScript"), clip.get("captionText"));
            assertTrue(((Number) clip.get("durationSeconds")).intValue() <= 8);
        });
    }

    @Test
    void routesSceneVoiceByExactDropdownValue() throws Exception {
        ScreenplayVideoService service = service();
        Method requireMethod = ScreenplayVideoService.class.getDeclaredMethod("requireSceneVoiceMethod", String.class);
        requireMethod.setAccessible(true);

        assertEquals("elevenlabs_v3_voice_clone", requireMethod.invoke(service, "elevenlabs_v3_voice_clone"));
        assertEquals("sarvam_voice_clone", requireMethod.invoke(service, "sarvam_voice_clone"));

        InvocationTargetException rejected = assertThrows(
                InvocationTargetException.class,
                () -> requireMethod.invoke(service, "ElevenLabs v3 Voice Clone")
        );
        assertTrue(rejected.getCause().getMessage().contains("Unsupported scene voice clone method"));
    }

    @Test
    void keepsProviderVoiceIdsScopedToSelectedMethod() throws Exception {
        ScreenplayVideoService service = service();
        Method providerIdMethod = ScreenplayVideoService.class.getDeclaredMethod(
                "providerVoiceIdForMethod",
                String.class,
                Map.class
        );
        providerIdMethod.setAccessible(true);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("providerVoiceId", "generic-eleven-id");
        profile.put("elevenLabsVoiceId", "eleven-id");
        profile.put("sarvamVoiceId", "sarvam-id");
        profile.put("minimaxVoiceId", "minimax-id");
        profile.put("localModels", Map.of("voiceModel", "elevenlabs_v3_voice_clone"));

        assertEquals("eleven-id", providerIdMethod.invoke(service, "elevenlabs_v3_voice_clone", profile));
        assertEquals("sarvam-id", providerIdMethod.invoke(service, "sarvam_voice_clone", profile));
        assertEquals("minimax-id", providerIdMethod.invoke(service, "fal_minimax_voice_clone", profile));
        assertEquals("", providerIdMethod.invoke(service, "fal_chatterbox_multilingual", profile));
    }

    @Test
    void reusesPersistedSceneCloneFromTopLevelAssetFingerprint() throws Exception {
        // matchesPersistedSceneDialogueAudio moved to FounderSceneAudioCloner (same package, owner
        // back-reference) - reflection now targets that class instead of ScreenplayVideoService.
        FounderSceneAudioCloner cloner = new FounderSceneAudioCloner(service(), null, null);
        Method matchesClone = FounderSceneAudioCloner.class.getDeclaredMethod(
                "matchesPersistedSceneDialogueAudio",
                Map.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        matchesClone.setAccessible(true);

        Map<String, Object> scene = Map.of(
                "dialogueCloneText", "Starting small makes the task feel safer.",
                "dialogueCloneLanguage", "English",
                "dialogueCloneVoiceModel", "client_rvc_english"
        );
        Map<String, Object> storedAsset = Map.of(
                "objectKey", "screenplay/scene-1-voice.wav",
                "dialogueFingerprint", "saved-fingerprint"
        );

        assertTrue((Boolean) matchesClone.invoke(
                cloner,
                scene,
                storedAsset,
                "saved-fingerprint",
                "Starting small makes the task feel safer.",
                "English",
                "client_rvc_english"
        ));
        assertTrue((Boolean) matchesClone.invoke(
                cloner,
                scene,
                storedAsset,
                "different-fingerprint",
                "Starting small makes the task feel safer.",
                "English",
                "client_rvc_english"
        ));
        assertFalse((Boolean) matchesClone.invoke(
                cloner,
                scene,
                storedAsset,
                "different-fingerprint",
                "This is different dialogue.",
                "English",
                "client_rvc_english"
        ));
        assertTrue((Boolean) matchesClone.invoke(
                cloner,
                scene,
                Map.of("objectKey", "screenplay/legacy-scene-1-voice.wav"),
                "new-fingerprint",
                "Starting small makes the task feel safer.",
                "English",
                "client_rvc_english"
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresPreparedSceneDialogueFromPersistedShotRowsAndKeepsFirstSceneUntouched() throws Exception {
        UUID scriptId = UUID.randomUUID();
        CreatorScriptShotRepository shots = mock(CreatorScriptShotRepository.class);
        when(shots.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(scriptId)).thenReturn(List.of(
                persistedShot(scriptId, 1, "Opening line."),
                persistedShot(scriptId, 2, "Lekin sach toh yeh hai, procrastination laziness nahi hai.",
                        "Iske peeche gehri psychological reasons hain."),
                persistedShot(scriptId, 3, "Jaise, fear of failure aur perfectionism.",
                        "Yeh humari feelings ko manage karne ka ek tareeka hai.")
        ));
        ScreenplayVideoService service = service(shots);
        CreatorScript script = CreatorScript.builder()
                .id(scriptId)
                .dialogueLanguage("Hinglish")
                .scriptPayload(Map.of("dialogueLanguage", "Hinglish"))
                .build();
        Map<String, Object> firstScene = new LinkedHashMap<>(Map.of(
                "id", "shot-1",
                "sceneNumber", 1,
                "shotNumber", 1,
                "dialogueScript", "Keep this approved first scene unchanged.",
                "clipUrl", "https://media.example/scene-1.mp4",
                "status", "VIDEO_READY"
        ));
        Map<String, Object> corruptedSecondScene = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", "shot-2"),
                Map.entry("sceneNumber", 2),
                Map.entry("shotNumber", 2),
                Map.entry("dialogueScript", "hain."),
                Map.entry("voiceover", "hain."),
                Map.entry("sourceShotNumber", 1),
                Map.entry("dialoguePart", 2),
                Map.entry("dialoguePartCount", 2),
                Map.entry("dialogueCloneText", "hain."),
                Map.entry("dialogueCloneStatus", "APPROVED"),
                Map.entry("dialogueAudio", Map.of("objectKey", "wrong-scene-2.wav"))
        ));
        Map<String, Object> thirdScene = new LinkedHashMap<>(Map.of(
                "id", "shot-3",
                "sceneNumber", 3,
                "shotNumber", 3,
                "dialogueScript", "Wrong third scene"
        ));
        Map<String, Object> overflowSplit = new LinkedHashMap<>(Map.of(
                "id", "scene-4",
                "sceneNumber", 4,
                "shotNumber", 4,
                "dialogueScript", "overflow",
                "storyboardSegmentation", "model_duration_capability"
        ));
        Map<String, Object> run = Map.of(
                "prepareOnly", true,
                "generationWorkflow", "scene_by_scene",
                "dialogueLanguage", "Hinglish",
                "sourceDialogueLanguage", "Hinglish"
        );

        Method reconcile = ScreenplayVideoService.class.getDeclaredMethod(
                "reconcilePreparedRunScenes",
                CreatorScript.class,
                Map.class,
                List.class
        );
        reconcile.setAccessible(true);
        List<Map<String, Object>> reconciled = (List<Map<String, Object>>) reconcile.invoke(
                service,
                script,
                run,
                List.of(firstScene, corruptedSecondScene, thirdScene, overflowSplit)
        );

        assertEquals(3, reconciled.size());
        assertEquals("Keep this approved first scene unchanged.", reconciled.get(0).get("dialogueScript"));
        assertEquals("https://media.example/scene-1.mp4", reconciled.get(0).get("clipUrl"));
        assertEquals(
                "Lekin sach toh yeh hai, procrastination laziness nahi hai. Iske peeche gehri psychological reasons hain.",
                reconciled.get(1).get("dialogueScript")
        );
        assertEquals("creator_script_shots", reconciled.get(1).get("dialogueSource"));
        assertEquals("NOT_REQUESTED", reconciled.get(1).get("dialogueCloneStatus"));
        assertFalse(reconciled.get(1).containsKey("dialogueAudio"));
        assertFalse(reconciled.get(1).containsKey("dialoguePart"));
        assertEquals(
                "Jaise, fear of failure aur perfectionism. Yeh humari feelings ko manage karne ka ek tareeka hai.",
                reconciled.get(2).get("dialogueScript")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void avatarWorkspaceRejectsUntrackedOhTranslationAndRemovesCreatorLabel() throws Exception {
        UUID scriptId = UUID.randomUUID();
        UUID videoRunId = UUID.randomUUID();
        CreatorScriptShotRepository shots = mock(CreatorScriptShotRepository.class);
        CreatorAvatarSceneDialogueRepository dialogues = mock(CreatorAvatarSceneDialogueRepository.class);

        CreatorScriptShot fourthShot = CreatorScriptShot.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .sequenceNumber(4)
                .sceneNumber(4)
                .shotNumber(4)
                .title("Shot 4")
                .dialogue(Map.of())
                .shotPayload(new LinkedHashMap<>(Map.of(
                        "shotNumber", 4,
                        "sceneNumber", 4,
                        "voiceOver",
                        "Creator: Kabhi-kabhi, lack of clarity ya overwhelm bhi procrastination ban jaata hai."
                )))
                .build();
        List<CreatorScriptShot> persistedShots = List.of(
                persistedShot(scriptId, 1, "Opening line."),
                persistedShot(
                        scriptId,
                        2,
                        "Lekin sach toh yeh hai, procrastination laziness nahi hai.",
                        "Iske peeche gehri psychological reasons hain."
                ),
                persistedShot(scriptId, 3, "Third line."),
                fourthShot
        );
        when(shots.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(scriptId))
                .thenReturn(persistedShots);

        List<Map<String, Object>> screenplayShots = persistedShots.stream()
                .map(CreatorScriptShot::getShotPayload)
                .toList();
        CreatorScript script = CreatorScript.builder()
                .id(scriptId)
                .tenantId("tenant")
                .userId("user")
                .dialogueLanguage("Hinglish")
                .scriptPayload(Map.of(
                        "dialogueLanguage", "Hinglish",
                        "hook", Map.of("type", "myth_busting"),
                        "shots", screenplayShots
                ))
                .shots(screenplayShots)
                .build();
        AvatarSceneDialogueService avatarDialogueService = new AvatarSceneDialogueService(
                dialogues,
                shots,
                new ObjectMapper()
        );
        ScreenplayVideoService service = service(shots, avatarDialogueService);

        List<Map<String, Object>> oldScenes = List.of(
                new LinkedHashMap<>(Map.of(
                        "sceneNumber", 1,
                        "dialogueScript", "Keep scene one unchanged."
                )),
                new LinkedHashMap<>(Map.of(
                        "sceneNumber", 2,
                        "dialogueScript", "Oh!",
                        "dialogueLanguage", "English",
                        "dialogueLocalizationStatus", "COMPLETED",
                        "dialogueTranslationApplied", true
                )),
                new LinkedHashMap<>(Map.of(
                        "sceneNumber", 3,
                        "dialogueScript", "Wrong scene three."
                )),
                new LinkedHashMap<>(Map.of(
                        "sceneNumber", 4,
                        "dialogueScript", "Creator: wrong old value"
                ))
        );
        Map<String, Object> run = Map.of(
                "runId", videoRunId.toString(),
                "prepareOnly", true,
                "generationWorkflow", "scene_by_scene",
                "founderLedHybridEnabled", true,
                "sourceDialogueLanguage", "Hinglish",
                "dialogueLanguage", "English"
        );
        Method reconcile = ScreenplayVideoService.class.getDeclaredMethod(
                "reconcilePreparedRunScenes",
                CreatorScript.class,
                Map.class,
                List.class
        );
        reconcile.setAccessible(true);

        List<Map<String, Object>> result = (List<Map<String, Object>>) reconcile.invoke(
                service,
                script,
                run,
                oldScenes
        );

        assertEquals("Keep scene one unchanged.", result.get(0).get("dialogueScript"));
        assertEquals(
                "Lekin sach toh yeh hai, procrastination laziness nahi hai. Iske peeche gehri psychological reasons hain.",
                result.get(1).get("dialogueScript")
        );
        assertEquals("avatar_screenplay_dto", result.get(1).get("dialogueSource"));
        assertEquals(
                "Kabhi-kabhi, lack of clarity ya overwhelm bhi procrastination ban jaata hai.",
                result.get(3).get("dialogueScript")
        );
        assertEquals("Creator", result.get(3).get("dialogueSpeaker"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinesOnlyAvailableSceneDialogueInSceneOrder() throws Exception {
        ScreenplayVideoService service = service();
        Method inputsMethod = ScreenplayVideoService.class.getDeclaredMethod(
                "sceneDialogueAudioInputs",
                List.class
        );
        inputsMethod.setAccessible(true);
        Method missingMethod = ScreenplayVideoService.class.getDeclaredMethod(
                "missingSceneDialogueAudioNumbers",
                List.class
        );
        missingMethod.setAccessible(true);
        Method fingerprintMethod = ScreenplayVideoService.class.getDeclaredMethod(
                "sceneDialogueAudioFingerprint",
                List.class,
                int.class
        );
        fingerprintMethod.setAccessible(true);

        List<Map<String, Object>> scenes = List.of(
                Map.of(
                        "id", "scene-1",
                        "sceneNumber", 1,
                        "dialogueAudio", Map.of(
                                "bucket", "creator-assets",
                                "objectKey", "scene-1.wav",
                                "contentType", "audio/wav"
                        )
                ),
                Map.of(
                        "id", "scene-2",
                        "sceneNumber", 2
                ),
                Map.of(
                        "id", "scene-3",
                        "sceneNumber", 3,
                        "dialogueAudio", Map.of(
                                "bucket", "creator-assets",
                                "objectKey", "scene-3.mp3",
                                "contentType", "audio/mpeg"
                        )
                )
        );

        List<Map<String, Object>> inputs = (List<Map<String, Object>>) inputsMethod.invoke(service, scenes);
        List<Integer> missing = (List<Integer>) missingMethod.invoke(service, scenes);
        String fingerprint = (String) fingerprintMethod.invoke(service, inputs, scenes.size());

        assertEquals(2, inputs.size());
        assertEquals(List.of(1, 3), inputs.stream().map(input -> (Integer) input.get("sceneNumber")).toList());
        assertEquals(List.of(2), missing);
        assertFalse(fingerprint.isBlank());

        List<Map<String, Object>> changedInputs = new java.util.ArrayList<>(inputs);
        changedInputs.set(1, new LinkedHashMap<>(changedInputs.get(1)));
        changedInputs.get(1).put("objectKey", "scene-3-new.mp3");
        assertFalse(fingerprint.equals(fingerprintMethod.invoke(service, changedInputs, scenes.size())));
    }

    private CreatorScriptShot persistedShot(UUID scriptId, int shotNumber, String... lines) {
        List<Map<String, Object>> dialogueLines = java.util.Arrays.stream(lines)
                .map(line -> Map.<String, Object>of("line", line))
                .toList();
        return CreatorScriptShot.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .sequenceNumber(shotNumber)
                .shotNumber(shotNumber)
                .title("Shot " + shotNumber)
                .dialogue(Map.of("Creator", dialogueLines))
                .shotPayload(new LinkedHashMap<>(Map.of(
                        "shotNumber", shotNumber,
                        "title", "Shot " + shotNumber,
                        "dialogue", Map.of("Creator", dialogueLines)
                )))
                .build();
    }

    private ScreenplayVideoService service() {
        return service(null, null);
    }

    private ScreenplayVideoService service(CreatorScriptShotRepository scriptShotRepository) {
        return service(scriptShotRepository, null);
    }

    private ScreenplayVideoService service(
            CreatorScriptShotRepository scriptShotRepository,
            AvatarSceneDialogueService avatarDialogueService
    ) {
        return new ScreenplayVideoService(
                null, // script repository
                scriptShotRepository,
                null, // storyboard repository
                null, // storyboard scene repository
                null, // shot plan repository
                null, // asset repository
                null, // generation job repository
                null, // prompt run repository
                null, // generation job service
                null, // creator AI service
                null, // provider generation service
                avatarDialogueService,
                null, // voice generation service
                null, // music generation service
                null, // asset storage service
                null, // billing wallet service
                null, // scene asset service
                null, // shot plan tag gateway
                null, // scene chat editor
                new AvatarDialogueSyncGatewayImpl(avatarDialogueService),
                new ObjectMapper(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }
}
