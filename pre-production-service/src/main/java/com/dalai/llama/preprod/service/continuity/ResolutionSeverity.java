package com.dalai.llama.preprod.service.continuity;

/** How a field's value was decided, which is what the UI shows. */
public enum ResolutionSeverity {
    /** Kept from the reference image; nothing competed with it. */
    PRESERVED,
    /** Kept from the reference image over a conflicting generated value, which was replaced. */
    OVERRIDE,
    /** A generated value differs from the reference but can coexist with it; nothing replaced. */
    WARNING,
    /** Changed because the shot or screenplay explicitly asks for it (quoted evidence). */
    EXPLICIT_TRANSITION,
    /** Set by the user, deliberately winning over continuity. */
    USER_OVERRIDE,
    /** The reference image establishes nothing here; the shot's own value applies. */
    APPLIED
}
