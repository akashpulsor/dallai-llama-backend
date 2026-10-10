package com.dalai.llama.tenant.showcase.domain;

/** How we know a PLATFORM item was made on Dalaillama (CREATOR_SHOWCASE.md rule 4). */
public enum PlatformProof {
    /** We uploaded it ourselves and hold the id YouTube returned. */
    UPLOADED,
    /** The creator uploaded it and pasted the link; it matched the project's film. */
    LINK_MATCH
}
