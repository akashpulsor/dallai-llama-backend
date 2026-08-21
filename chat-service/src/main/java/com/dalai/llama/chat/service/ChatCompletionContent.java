package com.dalai.llama.chat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The CHAT_WITH_ACTIONS task's response shape -- the model chooses between an ordinary reply and
 * proposing one of the actions it was told are available for this session, expressed as JSON
 * rather than native function-calling (llm-gateway's provider layer doesn't have a tool-calling
 * contract yet -- this is an honest, scoped-down substitute, not silently assumed to be the same
 * thing). {@code action}/{@code actionParameters} are null on an ordinary reply. {@code
 * actionParameters} is the one deliberate exception to this system's no-maps rule: it's the raw
 * boundary of genuinely free-form model output whose key set depends on which action was chosen,
 * and every {@code ChatActionExecutor} converts it into named, validated values immediately --
 * nothing downstream of the orchestrator ever holds onto the map itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionContent(
        String type,
        String replyContent,
        String action,
        Map<String, String> actionParameters
) {
    public boolean isAction() {
        return "action".equalsIgnoreCase(type) && action != null && !action.isBlank();
    }
}
