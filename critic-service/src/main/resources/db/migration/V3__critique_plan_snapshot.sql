-- Replaces critique_session's original_plan_json/revised_plan_json TEXT columns with real
-- relational storage: every ShotContext field flattened onto critique_plan_snapshot, with
-- characters/continuity anchors as proper child tables. No JSON anywhere in this schema.
ALTER TABLE critique_session
    DROP COLUMN IF EXISTS original_plan_json,
    DROP COLUMN IF EXISTS revised_plan_json;

CREATE TABLE IF NOT EXISTS critique_plan_snapshot (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES critique_session(id) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,
    shot_ref VARCHAR(64) NOT NULL,

    narrative_script_line TEXT,
    narrative_screenplay_slug VARCHAR(220),
    narrative_arc_position VARCHAR(16),

    environment_location VARCHAR(240),
    environment_time_of_day VARCHAR(16),
    environment_weather VARCHAR(240),
    environment_effects TEXT,

    lighting_key_light_note TEXT,
    lighting_mood VARCHAR(16),

    camera_shot_size VARCHAR(16),
    camera_note TEXT,

    product_is_hero_shot BOOLEAN,
    product_placement TEXT,
    product_ref_bucket VARCHAR(200),
    product_ref_object_key VARCHAR(500),

    technical_duration_seconds INTEGER,
    technical_aspect_ratio VARCHAR(16),
    technical_target_provider VARCHAR(120),
    technical_target_model VARCHAR(120),

    audio_ambient_description TEXT,
    audio_music_mood_note TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_critique_plan_snapshot UNIQUE (session_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_critique_plan_snapshot_session ON critique_plan_snapshot (session_id);

CREATE TABLE IF NOT EXISTS critique_plan_character (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id UUID NOT NULL REFERENCES critique_plan_snapshot(id) ON DELETE CASCADE,
    cast_id VARCHAR(120),
    face_ref_bucket VARCHAR(200),
    face_ref_object_key VARCHAR(500),
    wardrobe_note TEXT,
    performance_direction TEXT
);

CREATE INDEX IF NOT EXISTS idx_critique_plan_character_snapshot ON critique_plan_character (snapshot_id);

CREATE TABLE IF NOT EXISTS critique_plan_continuity_anchor (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id UUID NOT NULL REFERENCES critique_plan_snapshot(id) ON DELETE CASCADE,
    anchor_type VARCHAR(24),
    subject_id VARCHAR(200),
    description TEXT,
    reference_object_key VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_critique_plan_continuity_anchor_snapshot ON critique_plan_continuity_anchor (snapshot_id);
