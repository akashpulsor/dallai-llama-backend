package com.dalai.llama.videogen.dto;

import com.dalai.llama.videogen.domain.FlagState;

/**
 * Not the source doc's full admin-editable {@code feature_flag_definition} catalog (§19) -- the
 * two flags actually discussed, with code-level defaults. More are added the same way as
 * {@link com.dalai.llama.videogen.dto.shotcontext.ShotContext} fields: additive, nullable.
 */
public record FeatureFlags(
        FlagState dialogue,
        FlagState captions
) {
    public static final FeatureFlags DEFAULTS = new FeatureFlags(FlagState.ON, FlagState.OFF);

    /** Per-request override wins field-by-field; a null field falls back to {@code base}. */
    public FeatureFlags withOverride(FeatureFlags override) {
        if (override == null) {
            return this;
        }
        return new FeatureFlags(
                override.dialogue() != null ? override.dialogue() : this.dialogue(),
                override.captions() != null ? override.captions() : this.captions()
        );
    }
}
