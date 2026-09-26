-- Structural per-scene tag chosen by the creator at screenplay edit time; propagates to shots
-- (like needs_multi_image) so a model prompt has explicit intent about the shot kind. Stored
-- as text with a CHECK -- Postgres native enums would need a follow-up migration to extend and
-- our downstream (video-gen, llm-gateway) already treats strings.
--
-- Nullable on both tables: pre-existing rows stay untouched, and callers that don't set it read
-- as GENERIC in the domain layer.
ALTER TABLE screenplay_scene
    ADD COLUMN IF NOT EXISTS scene_type VARCHAR(24);

ALTER TABLE screenplay_scene
    DROP CONSTRAINT IF EXISTS screenplay_scene_scene_type_chk;
ALTER TABLE screenplay_scene
    ADD CONSTRAINT screenplay_scene_scene_type_chk
        CHECK (scene_type IS NULL OR scene_type IN
               ('IDENTITY', 'MOTION_GRAPHIC', 'LIVE_ACTION', 'PRODUCT_HERO', 'GENERIC'));

ALTER TABLE shot
    ADD COLUMN IF NOT EXISTS scene_type VARCHAR(24);

ALTER TABLE shot
    DROP CONSTRAINT IF EXISTS shot_scene_type_chk;
ALTER TABLE shot
    ADD CONSTRAINT shot_scene_type_chk
        CHECK (scene_type IS NULL OR scene_type IN
               ('IDENTITY', 'MOTION_GRAPHIC', 'LIVE_ACTION', 'PRODUCT_HERO', 'GENERIC'));
