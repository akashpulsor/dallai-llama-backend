package com.dalai.llama.chat.domain;

/** The closed set of actions chat can actually execute -- {@code ChatActionExecutor} beans are
 * looked up by this enum (strategy pattern), so adding a new action is a new bean + a new enum
 * constant, never a branch in the orchestrator. */
public enum ChatActionType {
    /** No scope required -- creates a new trend report; topic/industry/targetAudience come from
     * the model's free-text extraction of the conversation. */
    GENERATE_TREND_REPORT,
    /** Requires {@code ChatScopeType.MARKETING_PLAN} -- exports the session's bound plan, no
     * model-supplied parameters. */
    EXPORT_MARKETING_PLAN_PDF,
    /** Requires {@code ChatScopeType.MARKETING_PLAN} -- revises the session's bound plan using
     * model-extracted free-text instructions. */
    REVISE_MARKETING_PLAN,
    /** Requires {@code ChatScopeType.PRE_PRODUCTION_PROJECT} -- logs a pending change request
     * against one piece of the locked project (which piece + what change are both model-extracted
     * free text); a human on the creator's side reviews and applies it, this action never
     * regenerates anything itself. */
    SUGGEST_PRE_PRODUCTION_CHANGE
}
