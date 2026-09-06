package com.dalai.llama.videogen.domain;

/** Output resolution for a generated shot. Deliberately only the two tiers every registered
 * video model actually supports today -- Seedance's Fast variant (the default model) has no
 * 1080p option at all, and Wan's cost-policy default is capped at 480p (see FalAiProvider in
 * llm-gateway); offering 1080p here would let a caller pick something that silently downgrades
 * or is ignored server-side. Add a tier only once a registered model actually honors it.
 * {@code null} on a request means "use the provider's own default" (Seedance: 720p, Wan: 480p). */
public enum VideoResolution {
    RES_480P("480p"),
    RES_720P("720p");

    private final String wireValue;

    VideoResolution(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** Parses a caller-supplied wire value (e.g. a UI dropdown's raw string), or returns null for
     * a blank/unrecognized value rather than throwing -- an unrecognized resolution should fall
     * back to the provider's own default (see this enum's class javadoc), not fail the whole
     * prepare/generate call over one bad param. */
    public static VideoResolution fromWireValue(String wireValue) {
        if (wireValue == null || wireValue.isBlank()) {
            return null;
        }
        for (VideoResolution resolution : values()) {
            if (resolution.wireValue.equalsIgnoreCase(wireValue.trim())) {
                return resolution;
            }
        }
        return null;
    }
}
