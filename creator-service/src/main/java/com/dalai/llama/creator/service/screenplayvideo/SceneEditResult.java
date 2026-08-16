package com.dalai.llama.creator.service.screenplayvideo;

import java.util.Map;
import java.util.UUID;

/** Result of SceneChatEditor.generateEditedScene() - was ScreenplayVideoService's private AiSceneEditResult. */
public record SceneEditResult(
        Map<String, Object> scene,
        UUID promptRunId,
        Map<String, Object> providerOutput,
        String errorMessage
) {
    public boolean failed() {
        return errorMessage != null;
    }
}
