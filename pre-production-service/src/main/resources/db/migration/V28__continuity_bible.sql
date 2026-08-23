CREATE TABLE continuity_bible (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL UNIQUE,
    negative_prompt TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE continuity_lock (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    continuity_bible_id UUID NOT NULL REFERENCES continuity_bible(id) ON DELETE CASCADE,
    category VARCHAR(24) NOT NULL,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_continuity_lock_bible_id ON continuity_lock(continuity_bible_id);
