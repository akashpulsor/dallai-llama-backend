package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Parses the existing {@code Map<String,Object>} run JSONB blobs into {@link ScreenplayRunView},
 * the Run-level counterpart to {@link SceneViewMapper}. Same rules: this does NOT replace
 * persistence - the caller's original map is untouched and remains what's actually
 * read/written/stored everywhere else.
 */
public final class RunViewMapper {

    // Every ScreenplayRunView field is a nullable reference type, so Jackson's record
    // deserializer can build an all-null instance from an empty map - see SceneViewMapper for why
    // this is deliberately not a hand-written positional constructor call.
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();
    private static final ScreenplayRunView EMPTY = FALLBACK_MAPPER.convertValue(Map.of(), ScreenplayRunView.class);

    private RunViewMapper() {
    }

    public static ScreenplayRunView runView(Map<String, Object> raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        try {
            return objectMapper.convertValue(raw, ScreenplayRunView.class);
        } catch (IllegalArgumentException ex) {
            // Same reasoning as SceneViewMapper.sceneView: a genuine type mismatch falls back to
            // an empty view rather than throwing, since this is a read convenience layer and must
            // never become a new way for migrated call sites to break.
            return EMPTY;
        }
    }
}
