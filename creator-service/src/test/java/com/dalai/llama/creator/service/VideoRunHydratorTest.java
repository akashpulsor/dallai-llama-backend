package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the two genuinely standalone decision rules in VideoRunHydrator -
 * previously 0 test coverage (the whole cluster was reached only indirectly through
 * loadRun/hydrateVideoRunForResponse integration paths). Everything else in this class either
 * needs a live AssetStorageService (signed-URL refresh) or a real generationJobRepository
 * (audio-state recovery), so isn't worth characterizing in isolation here.
 */
class VideoRunHydratorTest {

    private final VideoRunHydrator hydrator = new VideoRunHydrator(null, null, null);

    @Test
    @SuppressWarnings("unchecked")
    void preferredFinalVideo_prefersCustomGeneratedVoiceOverVideoGeneratedAudioOverFirst() throws Exception {
        Method method = VideoRunHydrator.class.getDeclaredMethod("preferredFinalVideo", List.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> videoGenerated = Map.of("audioVariant", "VIDEO_GENERATED_AUDIO", "id", "video-generated");
        Map<String, Object> customGenerated = Map.of("audioVariant", "CUSTOM_GENERATED_VOICE", "id", "custom-generated");
        Map<String, Object> other = Map.of("audioVariant", "OTHER", "id", "other");

        Map<String, Object> result = (Map<String, Object>) method.invoke(
                hydrator, List.of(other, videoGenerated, customGenerated), Map.of()
        );
        assertEquals("custom-generated", result.get("id"));

        result = (Map<String, Object>) method.invoke(hydrator, List.of(other, videoGenerated), Map.of());
        assertEquals("video-generated", result.get("id"));

        result = (Map<String, Object>) method.invoke(hydrator, List.of(other), Map.of());
        assertEquals("other", result.get("id"));

        Map<String, Object> fallback = Map.of("id", "fallback");
        result = (Map<String, Object>) method.invoke(hydrator, List.of(), fallback);
        assertEquals("fallback", result.get("id"));
    }

    @Test
    void hasAudioState_detectsEachRecognizedAudioField() throws Exception {
        Method method = VideoRunHydrator.class.getDeclaredMethod("hasAudioState", Map.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(hydrator, Map.of()));
        assertFalse((Boolean) method.invoke(hydrator, (Object) null));

        assertTrue((Boolean) method.invoke(hydrator, Map.of("dialogueAudio", Map.of("objectKey", "x"))));
        assertTrue((Boolean) method.invoke(hydrator, Map.of("voiceTrack", "https://example.com/voice.mp3")));
        assertTrue((Boolean) method.invoke(hydrator, Map.of("combinedDialogueAudio", Map.of("objectKey", "x"))));
        assertTrue((Boolean) method.invoke(hydrator, Map.of("freeMusicSelectionPlan", Map.of("selected", "track-1"))));

        // Blank/empty values for every recognized key must NOT count as audio state present.
        assertFalse((Boolean) method.invoke(hydrator, Map.of(
                "dialogueAudio", Map.of(),
                "voiceTrack", "",
                "audioAssets", List.of()
        )));
    }
}
