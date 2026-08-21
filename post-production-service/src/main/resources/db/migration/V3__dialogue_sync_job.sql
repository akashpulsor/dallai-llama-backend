CREATE TABLE dialogue_sync_job (
    dialogue_sync_job_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID NOT NULL,
    project_id                 UUID NOT NULL,
    post_production_job_id     UUID NOT NULL REFERENCES post_production_job(job_id),
    shot_ref                   VARCHAR(128) NOT NULL,
    source_language             VARCHAR(16),
    target_language             VARCHAR(16),
    same_language               BOOLEAN NOT NULL,
    voice_profile_id            UUID REFERENCES voice_profile(voice_profile_id),
    llm_gateway_voice_clone_job_id VARCHAR(64),
    llm_gateway_lip_sync_job_id    VARCHAR(64),
    status                      VARCHAR(32) NOT NULL,
    output_bucket                VARCHAR(128),
    output_object_key            VARCHAR(1024),
    last_error                   TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at        TIMESTAMPTZ,
    completed_at                 TIMESTAMPTZ
);

CREATE INDEX idx_dialogue_sync_job_post_production_job ON dialogue_sync_job (post_production_job_id);
CREATE INDEX idx_dialogue_sync_job_tenant_project ON dialogue_sync_job (tenant_id, project_id);
