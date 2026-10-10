package com.dalai.llama.tenant.youtube.domain;

public enum ChannelStatus {
    /** Code issued; waiting for it to appear in the channel description. */
    PENDING,
    /** Ownership proven; videos imported. */
    VERIFIED
}
