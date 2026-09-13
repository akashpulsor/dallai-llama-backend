CREATE TABLE cloned_voice_audio (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    shot_id UUID NOT NULL,
    beat_id UUID,
    dialogue_text TEXT NOT NULL,
    mode VARCHAR(32) NOT NULL,
    provider_voice_id TEXT NOT NULL,
    bucket TEXT NOT NULL,
    object_key TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, resource_id)
);
