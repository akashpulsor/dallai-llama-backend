package com.dalai.llama.videogen.domain;

public enum ReferenceKind {
    CHARACTER_FACE,
    /** Reference audio for a character's voice -- see Character.voiceRef* on the ShotContext. */
    CHARACTER_VOICE,
    SET,
    STORYBOARD,
    STYLE_ANCHOR,
    PRIOR_SHOT_LAST_FRAME,
    PRODUCT_HERO,
    /** DP-lighting-plan image for the shot -- see Lighting.dpLightingImage* on the ShotContext. */
    DP_LIGHTING,
    /** Camera-plan diagram for the shot -- see Camera.cameraPlanImage* on the ShotContext. */
    CAMERA_PLAN_IMAGE,
    /** Pre-generated background music track -- see AudioAmbience.backgroundMusic* on the ShotContext. */
    BACKGROUND_MUSIC,
    /** Creator-uploaded multi-image reference on a shot flagged needsMultiImage (see
     * pre-production-service V66). Flows into reference_image_urls so the video model actually
     * sees the images -- captions live on the shot_reference_image row and (follow-up) will
     * feed the prompt text as well. */
    SHOT_REFERENCE
}
