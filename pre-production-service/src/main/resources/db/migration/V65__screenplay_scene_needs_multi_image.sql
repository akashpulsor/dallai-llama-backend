-- Per-scene creator flag + label for the multi-image reference upload flow. Phase 1: persist
-- these on the scene so a saved-edit of the screenplay keeps them. Phase 2 (follow-up
-- migration) propagates them to the shot(s) generated from this scene, and the shot page
-- reveals the actual multi-image upload UI using multi_image_label as the bundle name.
--
-- Default false + null: existing scenes stay untouched, new scenes only opt in when the
-- creator ticks the checkbox in ScreenplaySection.
ALTER TABLE screenplay_scene
    ADD COLUMN IF NOT EXISTS needs_multi_image BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS multi_image_label VARCHAR(120);
