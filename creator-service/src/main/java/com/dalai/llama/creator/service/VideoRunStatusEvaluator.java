package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's scene/run generation-status query cluster - whether every
 * scene already has a rendered clip, whether a previous run is resumable, which scene (if any) is
 * still actively generating, and the 10-92% progress-bar mapping used while scenes render. No
 * ScreenplayVideoService collaborators - every method only reads its own input and MapCoercion
 * statics - so this class takes no constructor arguments.
 */
final class VideoRunStatusEvaluator {

    boolean allScenesHaveClips(List<Map<String, Object>> scenes) {
        return scenes != null && !scenes.isEmpty() && scenes.stream().allMatch(this::hasClipAsset);
    }

    boolean isResumableVideoRun(Map<String, Object> run, CreatorGenerationJob latestVideoJob) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        if (scenes.isEmpty() || allScenesHaveClips(scenes)) {
            return false;
        }
        String latestJobStatus = latestVideoJob == null ? "" : firstText(latestVideoJob.getStatus()).toUpperCase(Locale.ROOT);
        if (latestJobStatus.contains("FAILED") || latestJobStatus.contains("CANCELLED")) {
            return true;
        }
        String status = firstText(run.get("status")).toUpperCase(Locale.ROOT);
        return status.contains("FAILED")
                || status.contains("PARTIAL")
                || status.contains("PAUSED")
                || status.contains("WAITING");
    }

    Map<String, Object> activeSceneGeneration(List<Map<String, Object>> scenes, String requestedSceneId) {
        if (scenes == null || scenes.isEmpty()) {
            return Map.of();
        }
        String requested = defaultString(requestedSceneId, "");
        for (Map<String, Object> scene : scenes) {
            String sceneId = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"), "");
            if (!requested.isBlank() && requested.equals(sceneId)) {
                continue;
            }
            if (isActiveSceneGenerationStatus(firstText(scene.get("status")))) {
                return scene;
            }
        }
        return Map.of();
    }

    private boolean isActiveSceneGenerationStatus(String status) {
        String value = defaultString(status, "").toUpperCase(Locale.ROOT);
        return value.equals("GENERATING_VIDEO")
                || value.equals("SCENE_GENERATION_QUEUED")
                || value.equals("VIDEO_GENERATION_QUEUED")
                || value.equals("PROVIDER_QUEUED")
                || value.equals("RUNNING")
                || value.equals("PENDING");
    }

    boolean hasClipAsset(Map<String, Object> scene) {
        return scene != null
                && !firstText(scene.get("bucket")).isBlank()
                && !firstText(scene.get("objectKey")).isBlank();
    }

    int sceneProgress(int completedScenes, int totalScenes) {
        int safeTotal = Math.max(1, totalScenes);
        int safeCompleted = Math.max(0, Math.min(completedScenes, safeTotal));
        return Math.max(10, Math.min(92, 10 + (safeCompleted * 82 / safeTotal)));
    }
}
