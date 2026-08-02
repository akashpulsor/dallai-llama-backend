package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorAvatarSceneDialogueRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvatarSceneDialogueServiceTest {

    private CreatorAvatarSceneDialogueRepository dialogueRepository;
    private CreatorScriptShotRepository shotRepository;
    private AvatarSceneDialogueService service;

    @BeforeEach
    void setUp() {
        dialogueRepository = mock(CreatorAvatarSceneDialogueRepository.class);
        shotRepository = mock(CreatorScriptShotRepository.class);
        when(shotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(any()))
                .thenReturn(List.of());
        service = new AvatarSceneDialogueService(
                dialogueRepository,
                shotRepository,
                new ObjectMapper()
        );
    }

    @Test
    void extractsOnlyCompleteSpokenLinesAndKeepsTrainingContext() {
        Map<String, Object> shotTwo = new LinkedHashMap<>();
        shotTwo.put("shotNumber", 2);
        shotTwo.put("sceneNumber", 2);
        shotTwo.put("dialogue", Map.of(
                "Creator", List.of(
                        Map.of(
                                "line", "Lekin sach toh yeh hai, procrastination laziness nahi hai.",
                                "subtext", "This is a common misunderstanding.",
                                "deliveryNote", "Decisive, strong denial."
                        ),
                        Map.of(
                                "line", "Iske peeche gehri psychological reasons hain.",
                                "subtext", "There is more to it.",
                                "deliveryNote", "Invite curiosity."
                        )
                )
        ));

        Map<String, Object> shotFour = new LinkedHashMap<>();
        shotFour.put("shotNumber", 4);
        shotFour.put("sceneNumber", 4);
        shotFour.put(
                "voiceOver",
                "Creator: Kabhi-kabhi, lack of clarity ya overwhelm bhi procrastination ban jaata hai."
        );

        Map<String, Object> screenplay = new LinkedHashMap<>();
        screenplay.put("dialogueLanguage", "Hinglish");
        screenplay.put("hook", Map.of("type", "myth_busting", "promise", "A psychological explanation"));
        screenplay.put("retentionPlan", Map.of("patternInterrupts", List.of("myth reveal")));
        screenplay.put("shots", List.of(shotTwo, shotFour));

        CreatorScript script = CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .userId("user")
                .dialogueLanguage("Hinglish")
                .scriptPayload(screenplay)
                .shots(List.of(shotTwo, shotFour))
                .build();

        List<AvatarSceneDialogueService.DialogueDraft> result = service.extractSourceDialogues(script);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).dialogueText()).isEqualTo(
                "Lekin sach toh yeh hai, procrastination laziness nahi hai. "
                        + "Iske peeche gehri psychological reasons hain."
        );
        assertThat(result.get(0).dialogueText())
                .doesNotContain("common misunderstanding", "Invite curiosity", "deliveryNote", "subtext");
        assertThat(result.get(0).speaker()).isEqualTo("Creator");
        assertThat(result.get(0).dialoguePayload())
                .containsKeys("structuredDialogue", "extractedLines", "speaker", "sourceKind", "sourcePath");
        assertThat(result.get(0).screenplayContext())
                .containsKeys("hook", "retentionPlan", "shot");

        assertThat(result.get(1).dialogueText()).isEqualTo(
                "Kabhi-kabhi, lack of clarity ya overwhelm bhi procrastination ban jaata hai."
        );
        assertThat(result.get(1).speaker()).isEqualTo("Creator");
        assertThat(result.get(1).dialogueText()).doesNotStartWith("Creator:");
    }

    @Test
    void keepsHinglishAndHindiAsSeparateLanguageVariants() {
        assertThat(service.languageKey("Hinglish")).isEqualTo("hinglish");
        assertThat(service.languageKey("Hindi")).isEqualTo("hindi");
        assertThat(service.languageCode("Hinglish")).isEqualTo("hi-IN");
        assertThat(service.languageCode("Hindi")).isEqualTo("hi-IN");
        assertThat(service.languageKey("English (India)")).isEqualTo("english_india");
        assertThat(service.languageCode("English (India)")).isEqualTo("en-IN");
    }

    @Test
    void translationReferencesCanonicalRootDialogue() {
        UUID rootId = UUID.randomUUID();
        UUID videoRunId = UUID.randomUUID();
        CreatorAvatarSceneDialogue source = CreatorAvatarSceneDialogue.builder()
                .id(rootId)
                .rootDialogueId(rootId)
                .tenantId("tenant")
                .userId("user")
                .videoRunId(videoRunId)
                .scriptId(UUID.randomUUID())
                .sceneNumber(2)
                .shotNumber(2)
                .sequenceNumber(2)
                .speaker("Creator")
                .dialogueRole("spoken_dialogue")
                .language("Hinglish")
                .languageKey("hinglish")
                .languageCode("hi-IN")
                .dialogueText("Source line")
                .sourceKind("structured_dialogue")
                .sourcePath("shots[1].dialogue")
                .sourceFingerprint("source-fingerprint")
                .dialoguePayload(new LinkedHashMap<>())
                .screenplayContext(new LinkedHashMap<>())
                .translationMetadata(new LinkedHashMap<>())
                .source(true)
                .current(true)
                .versionNumber(1)
                .build();

        when(dialogueRepository.findFirstByRootDialogueIdAndLanguageKeyAndCurrentTrue(rootId, "english"))
                .thenReturn(Optional.empty());
        when(dialogueRepository.findMaxVersionNumber(videoRunId, 2)).thenReturn(1);
        when(dialogueRepository.save(any(CreatorAvatarSceneDialogue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreatorAvatarSceneDialogue translation = service.saveTranslation(
                source,
                "English",
                "en-US",
                "Translated line",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "gemini",
                "gemini-model"
        );

        assertThat(translation.getRootDialogueId()).isEqualTo(rootId);
        assertThat(translation.getSource()).isFalse();
        assertThat(translation.getCurrent()).isTrue();
        assertThat(translation.getLanguage()).isEqualTo("English");
        assertThat(translation.getLanguageKey()).isEqualTo("english");
        assertThat(translation.getDialogueText()).isEqualTo("Translated line");
        assertThat(translation.getTranslationMetadata())
                .containsEntry("rootDialogueId", rootId.toString());
    }
}
