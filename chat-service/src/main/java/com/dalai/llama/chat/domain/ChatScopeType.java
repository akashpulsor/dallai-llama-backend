package com.dalai.llama.chat.domain;

/** What a chat session is bound to, if anything -- {@code scopeId} on {@code ChatSession} is only
 * meaningful when this isn't NONE. Actions that mutate an existing resource (export, revise) read
 * the target id from the session's own scope rather than trusting an id the model extracted from
 * free text -- the model can propose free-text parameters (e.g. revision instructions), never an
 * id to act on. */
public enum ChatScopeType {
    NONE,
    MARKETING_PLAN,
    /** A pre-production-service project once its client has locked the creative package (script,
     * screenplay, cast, shots, shot images) -- {@code scopeId} is the project id. Retrieval scopes
     * to everything embedded under that project id; actions target one specific piece of it by a
     * model-extracted reference, not the scope id itself (a project has many shots/scenes, unlike
     * MARKETING_PLAN's one-resource-per-session shape). */
    PRE_PRODUCTION_PROJECT
}
