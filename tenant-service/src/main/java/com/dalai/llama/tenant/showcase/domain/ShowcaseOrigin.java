package com.dalai.llama.tenant.showcase.domain;

/** Where a showcase video was made (CREATOR_SHOWCASE.md rule 4). Creators never set this. */
public enum ShowcaseOrigin {
    /** Made on Dalaillama and proven so (we uploaded it, or a pasted link matched a project). */
    PLATFORM,
    /** Anything else from the creator's channel. */
    EXTERNAL
}
