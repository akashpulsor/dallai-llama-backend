package com.dalai.llama.chat.service.action;

import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.entity.ChatSession;

import java.util.Map;
import java.util.UUID;

/**
 * One action chat can execute -- {@code ChatOrchestrator} looks these up by {@link
 * ChatActionType} from the full injected {@code List<ChatActionExecutor>} (strategy pattern), so
 * adding an action is a new bean, never a branch in the orchestrator. {@code parameters} is the
 * model's free-text extraction from the conversation (e.g. revision instructions) -- never an id
 * to act on; an executor that targets an existing resource reads the id from {@code
 * session.getScopeId()} instead, which the orchestrator validated came from how the session was
 * created, not from anything the model said.
 */
public interface ChatActionExecutor {

    ChatActionType actionType();

    ActionExecutionResult execute(UUID tenantId, ChatSession session, Map<String, String> parameters);
}
