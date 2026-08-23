package com.dalai.llama.preprod.domain;

/** Restores creator-service's real videoConsistencyBible categories (characterIdentityLocks/
 * wardrobeAndAppearanceLocks/setAndPropLocks/cameraLanguageLocks/lightingAndColorLocks) -- a
 * normalized child table ({@code ContinuityLock}) instead of a jsonb bag, per this build's own
 * no-maps discipline. */
public enum ContinuityLockCategory {
    CHARACTER_IDENTITY,
    WARDROBE_APPEARANCE,
    SET_PROP,
    CAMERA_LANGUAGE,
    LIGHTING_COLOR
}
