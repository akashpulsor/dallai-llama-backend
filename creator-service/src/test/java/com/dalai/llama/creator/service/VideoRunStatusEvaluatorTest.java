package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for VideoRunStatusEvaluator - the extraction of
 * ScreenplayVideoService's scene/run generation-status query cluster.
 */
class VideoRunStatusEvaluatorTest {

    private final VideoRunStatusEvaluator evaluator = new VideoRunStatusEvaluator();

    @Test
    void hasClipAsset_requiresBothBucketAndObjectKey() {
        assertTrue(evaluator.hasClipAsset(Map.of("bucket", "b", "objectKey", "k")));
        assertFalse(evaluator.hasClipAsset(Map.of("bucket", "b")));
        assertFalse(evaluator.hasClipAsset(Map.of()));
        assertFalse(evaluator.hasClipAsset(null));
    }

    @Test
    void allScenesHaveClips_requiresNonEmptyListWhereEveryEntryHasAClip() {
        assertFalse(evaluator.allScenesHaveClips(null));
        assertFalse(evaluator.allScenesHaveClips(List.of()));
        assertTrue(evaluator.allScenesHaveClips(List.of(Map.of("bucket", "b", "objectKey", "k"))));
        assertFalse(evaluator.allScenesHaveClips(List.of(
                Map.of("bucket", "b", "objectKey", "k"),
                Map.of("bucket", "b")
        )));
    }

    @Test
    void isResumableVideoRun_falseForNullOrEmptyRunOrWhenAllScenesHaveClips() {
        assertFalse(evaluator.isResumableVideoRun(null, null));
        assertFalse(evaluator.isResumableVideoRun(Map.of(), null));
        Map<String, Object> allClipped = Map.of("scenes", List.of(Map.of("bucket", "b", "objectKey", "k")));
        assertFalse(evaluator.isResumableVideoRun(allClipped, null));
    }

    @Test
    void isResumableVideoRun_trueWhenLatestJobFailedOrCancelled() {
        Map<String, Object> run = Map.of("scenes", List.of(Map.of("status", "QUEUED")));
        CreatorGenerationJob failedJob = new CreatorGenerationJob();
        failedJob.setStatus("FAILED");
        assertTrue(evaluator.isResumableVideoRun(run, failedJob));
    }

    @Test
    void isResumableVideoRun_trueWhenRunStatusIndicatesIncompleteState() {
        Map<String, Object> partialRun = Map.of("scenes", List.of(Map.of("status", "QUEUED")), "status", "PARTIAL_SCENE_CLIPS_READY");
        assertTrue(evaluator.isResumableVideoRun(partialRun, null));
    }

    @Test
    void isResumableVideoRun_falseWhenNoResumableSignal() {
        Map<String, Object> run = Map.of("scenes", List.of(Map.of("status", "QUEUED")), "status", "PLANNED");
        assertFalse(evaluator.isResumableVideoRun(run, null));
    }

    @Test
    void activeSceneGeneration_returnsFirstMatchingSceneExcludingRequestedId() {
        Map<String, Object> generatingScene = Map.of("id", "scene-2", "status", "GENERATING_VIDEO");
        List<Map<String, Object>> scenes = List.of(
                Map.of("id", "scene-1", "status", "READY"),
                generatingScene
        );
        assertEquals(generatingScene, evaluator.activeSceneGeneration(scenes, null));
        assertEquals(Map.of(), evaluator.activeSceneGeneration(scenes, "scene-2"));
    }

    @Test
    void activeSceneGeneration_returnsEmptyMapWhenNoneActive() {
        List<Map<String, Object>> scenes = List.of(Map.of("id", "scene-1", "status", "READY"));
        assertEquals(Map.of(), evaluator.activeSceneGeneration(scenes, null));
        assertEquals(Map.of(), evaluator.activeSceneGeneration(null, null));
    }

    @Test
    void sceneProgress_clampsBetween10And92() {
        assertEquals(10, evaluator.sceneProgress(0, 10));
        assertEquals(92, evaluator.sceneProgress(10, 10));
        assertEquals(51, evaluator.sceneProgress(5, 10));
    }

    @Test
    void sceneProgress_handlesZeroTotalScenesSafely() {
        assertEquals(10, evaluator.sceneProgress(0, 0));
    }
}
