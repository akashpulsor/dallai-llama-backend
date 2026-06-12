package com.dalai.llama.creator.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedScriptResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSingleStringValuesForAiNoteLists() {
        Map<String, Object> payload = Map.of(
                "projectTitle", "1947: Before and After Mountbatten",
                "dialogueCallbacks", "Return to the opening map question.",
                "toneAnchors", "informative",
                "shootingSchedule", "Single morning studio block.",
                "shots", List.of(Map.of(
                        "shotNumber", 1,
                        "primaryCharacters", "Priya",
                        "soundDesign", "Low historical pad under dialogue.",
                        "microNoveltyTriggers", "Map ripple on the transition.",
                        "editingNotes", "Cut on the word partition.",
                        "captionTrack", "Mountbatten arrives"
                ))
        );

        GeneratedScriptResponse.CinematicScript script = objectMapper.convertValue(
                payload,
                GeneratedScriptResponse.CinematicScript.class
        );

        assertThat(script.getDialogueCallbacks()).containsExactly("Return to the opening map question.");
        assertThat(script.getToneAnchors()).containsExactly("informative");
        assertThat(script.getShootingSchedule()).hasSize(1);
        assertThat(script.getShootingSchedule().get(0)).containsEntry("value", "Single morning studio block.");

        GeneratedScriptResponse.CinematicShot shot = script.getShots().get(0);
        assertThat(shot.getPrimaryCharacters()).containsExactly("Priya");
        assertThat(shot.getSoundDesign()).containsExactly("Low historical pad under dialogue.");
        assertThat(shot.getMicroNoveltyTriggers()).containsExactly("Map ripple on the transition.");
        assertThat(shot.getEditingNotes()).containsExactly("Cut on the word partition.");
        assertThat(shot.getCaptionTrack()).hasSize(1);
        assertThat(shot.getCaptionTrack().get(0)).containsEntry("value", "Mountbatten arrives");
    }
}
