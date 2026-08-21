package com.dalai.llama.chat.domain;

/** Set only on an ASSISTANT message that resulted from an action attempt -- null on ordinary
 * conversational replies. */
public enum ChatActionStatus {
    EXECUTED,
    FAILED
}
