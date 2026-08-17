package com.dalai.llama.creator.service;

import com.dalai.llama.creator.service.screenplayvideo.MapCoercion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's scene-context-compaction cluster - trimming a scene down
 * to the fields worth including as previous/next-scene context in AI prompts (compactScene),
 * filtering SRT cues to the ones overlapping a scene's time range (srtCuesForScene/overlaps), and
 * resolving a scene's stable id (sceneIdFor). No ScreenplayVideoService collaborators - every
 * method only reads its own input and MapCoercion statics - so this class takes no constructor
 * arguments.
 */
final class SceneContextCompactor {

    Map<String, Object> compactScene(Map<String, Object> scene) {
        if (scene == null || scene.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        List.of(
                "id",
                "sceneId",
                "sceneNumber",
                "shotNumber",
                "title",
                "durationSeconds",
                "startTime",
                "endTime",
                "generationMode",
                "providerPrompt",
                "prompt",
                "videoPrompt",
                "animationPrompt",
                "videoMotionPrompt",
                "imagePrompt",
                "storyboardImagePrompt",
                "cameraMovement",
                "negativePrompt",
                "noHumans",
                "seedancePrompt",
                "action",
                "description",
                "dialogue",
                "voiceover",
                "caption",
                "captionText",
                "hook",
                "openingHook",
                "hookLine",
                "retentionGoal",
                "patternInterrupt",
                "sceneDetail",
                "sceneDetails",
                "background",
                "backgroundDetail",
                "setting",
                "location",
                "environment",
                "setDescription",
                "character",
                "characters",
                "characterDetail",
                "characterDetails",
                "wardrobe",
                "props",
                "lighting",
                "camera",
                "shotType",
                "visualStyle",
                "brollStyle",
                "captionStyle",
                "adFormat",
                "adFormatKey",
                "formatStructure",
                "formatHookStyle",
                "formatRetentionStyle",
                "retentionGoal",
                "patternInterrupt"
        ).forEach(key -> {
            if (scene.containsKey(key)) {
                compact.put(key, scene.get(key));
            }
        });
        return compact;
    }

    List<Map<String, Object>> srtCuesForScene(List<Object> cues, Map<String, Object> scene) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        int start = intValue(scene.get("startSeconds"), 0);
        int end = intValue(scene.get("endSeconds"), start + positiveInt(scene.get("durationSeconds"), 15));
        return cues.stream()
                .map(MapCoercion::mapValue)
                .filter(cue -> cue.isEmpty()
                        || overlaps(start, end, intValue(firstValue(cue.get("startSeconds"), cue.get("start")), start), intValue(firstValue(cue.get("endSeconds"), cue.get("end")), end)))
                .limit(6)
                .toList();
    }

    private boolean overlaps(int startA, int endA, int startB, int endB) {
        return Math.max(startA, startB) < Math.min(endA, endB);
    }

    String sceneIdFor(Map<String, Object> scene, int sceneNumber) {
        String id = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"), scene.get("shotId"), scene.get("shot_id"));
        if (!id.isBlank()) {
            return id;
        }
        return "scene-" + sceneNumber;
    }
}
