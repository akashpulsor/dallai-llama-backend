package com.dalai.llama.tenant.showcase.domain;

/** Where an item plays from (CREATOR_SHOWCASE.md rule 5). */
public enum VideoHostType {
    /** The YouTube embed (default). */
    YOUTUBE,
    /** Our own copy of a platform film, via a short-lived signed link (fallback). */
    SELF
}
