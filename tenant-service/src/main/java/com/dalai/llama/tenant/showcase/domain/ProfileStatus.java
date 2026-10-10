package com.dalai.llama.tenant.showcase.domain;

/** Visibility of a creator's public profile. Only {@link #ACTIVE} profiles are served publicly. */
public enum ProfileStatus {
    /** Visible. */
    ACTIVE,
    /** The creator's subscription lapsed; links show "not available" until it is active again. */
    SUSPENDED,
    /** Hidden by ops (kill switch). */
    HIDDEN
}
