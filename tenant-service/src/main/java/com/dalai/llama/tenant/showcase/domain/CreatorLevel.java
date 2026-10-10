package com.dalai.llama.tenant.showcase.domain;

/** The shaping ladder (CREATOR_SHOWCASE.md rule 11). Ordered: a higher ordinal is a higher level,
 * and the stored codes ("L0".."L4") sort the same way. */
public enum CreatorLevel {
    /** No verified channel, or not enough showable videos yet. */
    L0,
    /** Starter: verified channel, enough picks, basics filled in. */
    L1,
    /** Maker: at least one film made on Dalaillama published. */
    L2,
    /** Proven: a verified-funded platform film within the window. */
    L3,
    /** Star: several funded films in the window, or brand requests that became briefs. */
    L4;

    public boolean atLeast(CreatorLevel other) {
        return ordinal() >= other.ordinal();
    }

    public CreatorLevel next() {
        return this == L4 ? L4 : values()[ordinal() + 1];
    }

    public CreatorLevel previous() {
        return this == L0 ? L0 : values()[ordinal() - 1];
    }
}
