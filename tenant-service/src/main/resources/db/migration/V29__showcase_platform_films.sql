-- Creator Showcase, Phase A slice 4: films made on Dalaillama (CREATOR_SHOWCASE.md rules 3-5, 8-10).
--
-- A PLATFORM item is linked to the project it came from and carries the raw facts its ranking and
-- mail eligibility depend on, copied from pre-production when it is published: when the client
-- locked (paid in full), when it passed the evidence gate, and when the client accepted marketing
-- use. `channel` says whose YouTube channel holds the copy; `host` says where it plays from.

ALTER TABLE showcase_item ADD COLUMN platform_proof        VARCHAR(16);
ALTER TABLE showcase_item ADD COLUMN channel               VARCHAR(10);
ALTER TABLE showcase_item ADD COLUMN host                  VARCHAR(8)  NOT NULL DEFAULT 'YOUTUBE';
ALTER TABLE showcase_item ADD COLUMN client_locked_at      TIMESTAMPTZ;
ALTER TABLE showcase_item ADD COLUMN funded_verified_at    TIMESTAMPTZ;
ALTER TABLE showcase_item ADD COLUMN marketing_consent_at  TIMESTAMPTZ;

ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_proof_ck
    CHECK (platform_proof IS NULL OR platform_proof IN ('UPLOADED', 'LINK_MATCH'));
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_channel_ck
    CHECK (channel IS NULL OR channel IN ('OFFICIAL', 'CREATOR'));
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_host_ck CHECK (host IN ('YOUTUBE', 'SELF'));
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_platform_ck
    CHECK (origin = 'EXTERNAL' OR (source_project_id IS NOT NULL AND platform_proof IS NOT NULL));

-- Uploads to Dalaillama's own YouTube channel: a queue row per film, one worker, one at a time.
CREATE TABLE official_upload_job (
    id               UUID         PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    project_id       UUID         NOT NULL,
    industry         VARCHAR(32)  NOT NULL,
    format           VARCHAR(32)  NOT NULL,
    client_label     VARCHAR(80),
    status           VARCHAR(16)  NOT NULL,
    youtube_video_id VARCHAR(16),
    attempts         INT          NOT NULL DEFAULT 0,
    error            VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT official_upload_job_project_uk UNIQUE (project_id),
    CONSTRAINT official_upload_job_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT official_upload_job_status_ck
        CHECK (status IN ('QUEUED', 'UPLOADING', 'DONE', 'FAILED', 'NEEDS_MANUAL'))
);

CREATE INDEX ix_official_upload_job_queue ON official_upload_job (status, created_at);
