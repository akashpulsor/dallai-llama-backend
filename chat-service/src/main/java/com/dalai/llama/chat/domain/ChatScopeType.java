package com.dalai.llama.chat.domain;

/** What a chat session is bound to, if anything -- {@code scopeId} on {@code ChatSession} is only
 * meaningful when this isn't NONE. Actions that mutate an existing resource (export, revise) read
 * the target id from the session's own scope rather than trusting an id the model extracted from
 * free text -- the model can propose free-text parameters (e.g. revision instructions), never an
 * id to act on. */
public enum ChatScopeType {
    NONE,
    MARKETING_PLAN
}
