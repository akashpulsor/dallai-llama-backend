package com.dalai.llama.tenant.showcase.domain;

public enum ShowcaseItemStatus {
    /** On the profile. */
    LIVE,
    /** Kept but not shown; the creator can show it again. */
    HIDDEN,
    /** Taken off by the creator; kept only for history. */
    REMOVED
}
