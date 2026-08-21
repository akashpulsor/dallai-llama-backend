CREATE TABLE IF NOT EXISTS screenplay (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL UNIQUE REFERENCES project(id) ON DELETE CASCADE,
    script_id UUID NOT NULL REFERENCES script(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS screenplay_scene (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    screenplay_id UUID NOT NULL REFERENCES screenplay(id) ON DELETE CASCADE,
    scene_number INTEGER NOT NULL,
    slug VARCHAR(220) NOT NULL,
    location VARCHAR(240),
    time_of_day VARCHAR(16),
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_screenplay_scene UNIQUE (screenplay_id, scene_number)
);

CREATE INDEX IF NOT EXISTS idx_screenplay_scene_screenplay ON screenplay_scene (screenplay_id, scene_number);
