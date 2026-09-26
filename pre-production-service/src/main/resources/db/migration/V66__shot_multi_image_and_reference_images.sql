-- Phase 2 of the screenplay -> shot -> upload multi-image reference flow (see V65 for the
-- screenplay-side flag/label). This migration:
--
-- 1. Mirrors ScreenplayScene.needsMultiImage + multiImageLabel onto Shot -- shot-list generation
--    now copies the two fields from the parent scene, so a shot page can reveal the multi-image
--    upload UI without having to re-join back to screenplay_scene at read time. Default false /
--    null keeps every existing shot untouched.
--
-- 2. Creates shot_reference_image, one row per uploaded image on a multi-image shot. The
--    creator uploads N images through the new endpoint; each row carries the MinIO bucket +
--    object_key plus an optional caption/tag that the video-generation prompt uses to reference
--    the image ("app-flow step 1", "the empty state", etc). ordinal preserves upload order for
--    the storyboard and the eventual PDF export.

ALTER TABLE shot
    ADD COLUMN IF NOT EXISTS needs_multi_image BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS multi_image_label VARCHAR(120);

CREATE TABLE IF NOT EXISTS shot_reference_image (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    shot_id       UUID NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    bucket        VARCHAR(120) NOT NULL,
    object_key    VARCHAR(512) NOT NULL,
    content_type  VARCHAR(120),
    caption       VARCHAR(240),
    ordinal       INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Read pattern: list every reference image for a shot, in creator-ordered ordinal order (that's
-- the same order the storyboard tile shows them and the video-gen prompt walks them).
CREATE INDEX IF NOT EXISTS idx_shot_reference_image_shot_ordinal
    ON shot_reference_image(shot_id, ordinal ASC);
