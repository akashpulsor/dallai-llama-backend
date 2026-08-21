CREATE TABLE IF NOT EXISTS shot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    screenplay_scene_id UUID NOT NULL REFERENCES screenplay_scene(id) ON DELETE CASCADE,
    shot_ref VARCHAR(64) NOT NULL,
    shot_number INTEGER NOT NULL,
    shot_type VARCHAR(16) NOT NULL,
    script_line TEXT,
    primary_character_key VARCHAR(160),
    camera_shot_size VARCHAR(16),
    camera_note TEXT,
    location VARCHAR(240),
    time_of_day VARCHAR(16),
    lighting_mood VARCHAR(16),
    duration_seconds INTEGER,
    aspect_ratio VARCHAR(16),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shot_ref UNIQUE (project_id, shot_ref)
);

CREATE INDEX IF NOT EXISTS idx_shot_project ON shot (project_id, shot_number);
CREATE INDEX IF NOT EXISTS idx_shot_scene ON shot (screenplay_scene_id, shot_number);
