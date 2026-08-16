package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorScript;

import java.util.Map;
import java.util.UUID;

/**
 * Calls the AI provider to produce an edited scene for a chat-requested change, and accounts for
 * it: saves a CreatorPromptRun, publishes the billing debit, falls back to a text-only revision if
 * the AI call fails rather than losing the user's request entirely. This is genuinely
 * self-contained - no ScreenplayVideoService state beyond CreatorAiService/CreatorPromptRunRepository
 * - which is what lets it live behind a real interface without a circular dependency back into the
 * orchestrator that calls it (ScreenplayVideoService.chatScene(), which still owns job lifecycle,
 * RAG context assembly, and merging the result into the run - those need loadRun/loadScript/
 * buildRagContext/etc., which would create exactly that cycle if pulled in here too).
 */
public interface SceneChatEditor {

    SceneEditResult generateEditedScene(
            CreatorScript script,
            Map<String, Object> scene,
            String message,
            Map<String, Object> ragContext,
            String renderedPrompt,
            UUID jobId
    );
}
