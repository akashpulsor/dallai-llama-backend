package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the two pure decision rules in FinalVideoRenderer -
 * previously 0 test coverage. The actual ffmpeg merge/render methods need real file I/O and an
 * AssetStorageService, so aren't worth characterizing in isolation here.
 */
class FinalVideoRendererTest {

    private final FinalVideoRenderer renderer = new FinalVideoRenderer(null, null);

    @Test
    void hasMergeableClip_requiresBothBucketAndObjectKey() {
        assertTrue(renderer.hasMergeableClip(Map.of("bucket", "b", "objectKey", "k")));
        assertFalse(renderer.hasMergeableClip(Map.of("bucket", "b")));
        assertFalse(renderer.hasMergeableClip(Map.of("objectKey", "k")));
        assertFalse(renderer.hasMergeableClip(Map.of()));
        assertFalse(renderer.hasMergeableClip(null));
    }

    @Test
    void acceptedScenesCoverAll_requiresEveryReturnedSceneToBeExplicitlyAcceptedOrIdMatched() {
        Map<String, Object> scene1 = Map.of("id", "scene-1", "sceneNumber", 1);
        Map<String, Object> scene2 = Map.of("id", "scene-2", "sceneNumber", 2, "accepted", true);
        Map<String, Object> scene3 = Map.of("id", "scene-3", "sceneNumber", 3, "clipAccepted", true);
        List<Map<String, Object>> scenes = List.of(scene1, scene2, scene3);

        assertTrue(renderer.acceptedScenesCoverAll(scenes, List.of("scene-1")));
        assertFalse(renderer.acceptedScenesCoverAll(scenes, List.of("scene-2", "scene-3")));
        assertFalse(renderer.acceptedScenesCoverAll(scenes, List.of()));
        assertFalse(renderer.acceptedScenesCoverAll(List.of(), List.of("scene-1")));
    }

    @Test
    void acceptedScenesCoverAll_matchesBySceneNumberWhenIdIsNotInTheAcceptedList() {
        Map<String, Object> scene = Map.of("id", "scene-1", "sceneNumber", 4);
        assertTrue(renderer.acceptedScenesCoverAll(List.of(scene), List.of("4")));
    }
}
