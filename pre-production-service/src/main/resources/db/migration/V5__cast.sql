CREATE TABLE IF NOT EXISTS cast_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID REFERENCES project(id) ON DELETE CASCADE,
    display_name VARCHAR(160) NOT NULL,
    face_ref_bucket VARCHAR(200) NOT NULL,
    face_ref_object_key VARCHAR(500) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN cast_profile.project_id IS
    'NULL = reusable library entry, same convention as creator-service''s real creator_profiles.project_id.';

CREATE INDEX IF NOT EXISTS idx_cast_profile_tenant_project ON cast_profile (tenant_id, project_id);

CREATE TABLE IF NOT EXISTS cast_assignment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    script_character_id UUID NOT NULL REFERENCES script_character(id) ON DELETE CASCADE,
    cast_profile_id UUID NOT NULL REFERENCES cast_profile(id) ON DELETE RESTRICT,
    wardrobe_note TEXT,
    performance_direction TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cast_assignment UNIQUE (project_id, script_character_id)
);
