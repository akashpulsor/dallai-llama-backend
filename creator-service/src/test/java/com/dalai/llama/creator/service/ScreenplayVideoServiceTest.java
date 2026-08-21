package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Characterization tests for ScreenplayVideoService.sceneEditResponseMessage - the chatScene()
 * response message. Previously a fixed "Scene edit saved with RAG context." string regardless of
 * what the AI editor actually did, which was confusing when an edit request legitimately
 * required no text change (e.g. a pronunciation complaint where the underlying dialogue was
 * already spelled correctly - see SceneViewMapperTest/ScreenplayVideoProviderGenerationServiceTest
 * for the related real-data investigation). Now surfaces the AI's own editingNotes plus an
 * explicit next-step pointer. Invoked via reflection on a mock (Mockito never intercepts private
 * methods, so this executes the real method body) since ScreenplayVideoService has no
 * lightweight test constructor - same pattern as SceneRegenerationJobStarterTest's owner mock.
 */
class ScreenplayVideoServiceTest {

    private final ScreenplayVideoService service = mock(ScreenplayVideoService.class);

    @Test
    void sceneEditResponseMessage_includesEditingNotesAndRegenerateCallToAction() throws Exception {
        Map<String, Object> editedScene = new LinkedHashMap<>();
        editedScene.put("editingNotes", "All instances of 'Jaipur' were already correctly spelled - no textual changes were required.");
        String message = invoke(editedScene);
        assertEquals(
                "Scene edit saved. All instances of 'Jaipur' were already correctly spelled - no textual changes were required. "
                        + "Click Regenerate on this shot to apply the change to the video.",
                message
        );
    }

    @Test
    void sceneEditResponseMessage_omitsEditingNotesSentenceWhenBlank() throws Exception {
        Map<String, Object> editedScene = new LinkedHashMap<>();
        String message = invoke(editedScene);
        assertEquals("Scene edit saved. Click Regenerate on this shot to apply the change to the video.", message);
    }

    private String invoke(Map<String, Object> editedScene) throws Exception {
        var method = ScreenplayVideoService.class.getDeclaredMethod("sceneEditResponseMessage", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(service, editedScene);
    }
}
