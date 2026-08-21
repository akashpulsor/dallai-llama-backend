CREATE TABLE IF NOT EXISTS script (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL UNIQUE REFERENCES project(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    script_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS script_character (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    script_id UUID NOT NULL REFERENCES script(id) ON DELETE CASCADE,
    character_key VARCHAR(160) NOT NULL,
    character_name VARCHAR(160) NOT NULL,
    character_role VARCHAR(120),
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_script_character UNIQUE (script_id, character_key)
);

CREATE INDEX IF NOT EXISTS idx_script_character_script ON script_character (script_id);
