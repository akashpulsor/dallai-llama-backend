package com.dalai.llama.chat.service.action;

/** {@code summary} becomes the assistant chat message's content either way -- a human-readable
 * account of what happened (or what went wrong), never a raw error dump. */
public record ActionExecutionResult(
        boolean success,
        String summary
) {
    public static ActionExecutionResult success(String summary) {
        return new ActionExecutionResult(true, summary);
    }

    public static ActionExecutionResult failure(String summary) {
        return new ActionExecutionResult(false, summary);
    }
}
