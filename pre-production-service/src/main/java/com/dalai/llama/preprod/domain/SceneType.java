package com.dalai.llama.preprod.domain;

/** Creator-set structural tag on a screenplay scene -- reaches the shot page (via inheritance)
 * and the video-gen prompt so a model has explicit intent about the kind of shot this is. Kept
 * intentionally coarse: five buckets that meaningfully change prompt composition (identity beat,
 * motion graphic overlay, live-action, product hero, generic). Nullable on the row for
 * back-compat with pre-existing scenes; a null scene_type reads as GENERIC downstream. */
public enum SceneType {
    IDENTITY,
    MOTION_GRAPHIC,
    LIVE_ACTION,
    PRODUCT_HERO,
    GENERIC
}
