package com.dalai.llama.postprod.domain;

/** Where a cut stands relative to the one the film currently uses. */
public enum ClipVersionStatus {

    /**
     * Made, watchable, and not yet in the film.
     *
     * <p>The state that makes replacing a clip safe. A new cut used to overwrite the old one the
     * moment it was produced, so the only way to find out whether it was any good was to lose the
     * alternative. A preview costs one object in storage and removes that trade entirely.
     */
    PREVIEW,

    /** The cut the film uses. Exactly one per shot, enforced by a partial unique index. */
    ACTIVE,

    /** Was ACTIVE, is not any more. Kept, never deleted -- going back is the whole point. */
    SUPERSEDED
}
