CREATE TABLE shot_prompt (
    prompt_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                    UUID NOT NULL REFERENCES video_gen_job(job_id),
    tenant_id                 UUID NOT NULL,   -- denormalized: query/safety, see design doc §4.1
    project_id                UUID NOT NULL,   -- denormalized: query/safety, see design doc §4.1
    created_by                UUID NOT NULL,   -- who created THIS version (may differ from job.created_by)
    parent_prompt_id          UUID REFERENCES shot_prompt(prompt_id),
    variant_label             VARCHAR(64),
    prompt_original            TEXT NOT NULL,
    prompt_compressed         TEXT,
    compression_applied       BOOLEAN NOT NULL DEFAULT false,
    original_length            INTEGER,
    compressed_length         INTEGER,
    named_entities_validated  BOOLEAN,
    negative_prompt           TEXT NOT NULL DEFAULT '',
    dialogue_flag              VARCHAR(8) NOT NULL DEFAULT 'ON',
    captions_flag              VARCHAR(8) NOT NULL DEFAULT 'OFF',
    shipped                   BOOLEAN NOT NULL DEFAULT false,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shot_prompt_job ON shot_prompt (job_id);
CREATE INDEX idx_shot_prompt_tenant_project_created ON shot_prompt (tenant_id, project_id, created_at DESC);
CREATE UNIQUE INDEX idx_shot_prompt_shipped ON shot_prompt (job_id) WHERE shipped = true;
