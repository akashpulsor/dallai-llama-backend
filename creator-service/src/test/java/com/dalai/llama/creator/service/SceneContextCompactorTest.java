package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for SceneContextCompactor - the extraction of
 * ScreenplayVideoService's scene-context-compaction cluster.
 */
class SceneContextCompactorTest {

    private final SceneContextCompactor compactor = new SceneContextCompactor();

    @Test
    void compactScene_returnsEmptyMapForNullOrEmptyScene() {
        assertTrue(compactor.compactScene(null).isEmpty());
        assertTrue(compactor.compactScene(Map.of()).isEmpty());
    }

    @Test
    void compactScene_keepsOnlyAllowlistedFieldsThatArePresent() {
        Map<String, Object> scene = Map.of(
                "id", "scene-1",
                "title", "Hook",
                "internalDebugField", "should not appear",
                "runId", "should not appear either"
        );
        Map<String, Object> compact = compactor.compactScene(scene);
        assertEquals("scene-1", compact.get("id"));
        assertEquals("Hook", compact.get("title"));
        assertFalse(compact.containsKey("internalDebugField"));
        assertFalse(compact.containsKey("runId"));
    }

    @Test
    void srtCuesForScene_returnsEmptyListForNullOrEmptyCues() {
        assertTrue(compactor.srtCuesForScene(null, Map.of()).isEmpty());
        assertTrue(compactor.srtCuesForScene(List.of(), Map.of()).isEmpty());
    }

    @Test
    void srtCuesForScene_keepsOnlyCuesOverlappingSceneTimeRange() {
        Map<String, Object> scene = Map.of("startSeconds", 10, "endSeconds", 20);
        List<Object> cues = List.of(
                Map.of("startSeconds", 5, "endSeconds", 12),
                Map.of("startSeconds", 25, "endSeconds", 30),
                Map.of("startSeconds", 15, "endSeconds", 18)
        );
        List<Map<String, Object>> kept = compactor.srtCuesForScene(cues, scene);
        assertEquals(2, kept.size());
    }

    @Test
    void srtCuesForScene_limitsResultToSixCues() {
        Map<String, Object> scene = Map.of("startSeconds", 0, "endSeconds", 1000);
        List<Object> cues = List.of(
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10),
                Map.of("startSeconds", 0, "endSeconds", 10)
        );
        assertEquals(6, compactor.srtCuesForScene(cues, scene).size());
    }

    @Test
    void sceneIdFor_prefersExistingIdOverFallback() {
        assertEquals("existing-id", compactor.sceneIdFor(Map.of("id", "existing-id"), 3));
        assertEquals("from-scene-id", compactor.sceneIdFor(Map.of("sceneId", "from-scene-id"), 3));
    }

    @Test
    void sceneIdFor_fallsBackToSceneNumberWhenNoIdPresent() {
        assertEquals("scene-3", compactor.sceneIdFor(Map.of(), 3));
    }
}
