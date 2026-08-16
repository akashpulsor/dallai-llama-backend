package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Parses the existing {@code Map<String,Object>} scene JSONB blobs into
 * {@link ScreenplaySceneView}, the same way {@code ShotPlanTagMapper} does for shot-plan tags.
 * This does NOT replace persistence - the caller's original map is untouched and remains what's
 * actually read/written/stored everywhere else. Parsing here is purely for type-safe,
 * autocomplete-friendly READ access in place of scattered {@code map.get("key")} lookups.
 *
 * <p>Never write a view object back into storage in place of the original map - any field this
 * view doesn't model would be silently dropped. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * on the view means a field this mapper doesn't model yet doesn't break parsing; it's just
 * invisible to the typed view (the original map, which is what's actually persisted, still has it).
 */
public final class SceneViewMapper {

    // Every ScreenplaySceneView field is a nullable reference type (no primitives), so Jackson's
    // record deserializer can build an all-null instance from an empty map - deliberately not a
    // hand-written 90-argument constructor call, which would be exactly the kind of easy-to-
    // miscount, hard-to-verify code this view exists to get away from.
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();
    private static final ScreenplaySceneView EMPTY = FALLBACK_MAPPER.convertValue(Map.of(), ScreenplaySceneView.class);

    private SceneViewMapper() {
    }

    public static ScreenplaySceneView sceneView(Map<String, Object> raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        try {
            return objectMapper.convertValue(raw, ScreenplaySceneView.class);
        } catch (IllegalArgumentException ex) {
            // A parse failure here means the stored data has a genuine type mismatch against this
            // view's declared field types (not just an extra/unmodeled field, which
            // ignoreUnknown already tolerates) - fall back to an empty view rather than throw,
            // since this is a read convenience layer and must never become a new way for
            // migrated call sites to break. The original map (what's actually used everywhere
            // else) is untouched either way.
            return EMPTY;
        }
    }
}
