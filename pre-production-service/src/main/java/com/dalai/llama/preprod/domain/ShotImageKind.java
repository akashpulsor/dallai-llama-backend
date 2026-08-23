package com.dalai.llama.preprod.domain;

/** The 4 image types generated per shot, mirroring creator-service's real
 * ASSET_TYPE_STORYBOARD_IMAGE / ASSET_TYPE_PRODUCTION_IMAGE_ANCHOR /
 * ASSET_TYPE_LIGHTING_BUILD_SHEET_IMAGE / ASSET_TYPE_CAMERA_PLAN_SHEET_IMAGE constants -- same 4
 * kinds, now a real enum with its own storage row per kind instead of ad-hoc string keys. */
public enum ShotImageKind {
    /** Hand-drawn technical planning sketch -- driven by {@code Shot.sketchPrompt}, generated
     * eagerly alongside the rest of the shot list. */
    STORYBOARD,
    /** Photoreal finished commercial frame -- the actual image-to-video anchor. Identity-
     * conditioned when the shot's primary character has a resolved CastProfile. */
    PRODUCTION,
    /** Rookie-executable lighting build sheet: light placement, phone position, shadows, setup
     * steps. */
    LIGHTING,
    /** Shoot-ready DP camera plan: position, lens, framing box, movement path. */
    CAMERA_PLAN
}
