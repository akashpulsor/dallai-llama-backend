-- A creator's uploaded film (tenant-service's creator-assets bucket) waiting for its Dalaillama-
-- stamped copy before it goes on Dalaillama's YouTube channel. The id is the caller's (tenant-
-- service's upload job), so asking twice is one request. Stamped by the same one-at-a-time Kafka
-- consumer as films; status NULL = due, then PENDING / DONE / FAILED like film_render.
CREATE TABLE upload_stamp (
    id                  UUID          PRIMARY KEY,
    tenant_id           UUID          NOT NULL,
    source_bucket       VARCHAR(63)   NOT NULL,
    source_key          VARCHAR(512)  NOT NULL,
    width               INT,
    status              VARCHAR(16),
    attempts            INT           NOT NULL DEFAULT 0,
    requested_at        TIMESTAMPTZ,
    stamped_object_key  VARCHAR(512),
    error               VARCHAR(500),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_upload_stamp_due ON upload_stamp (created_at) WHERE stamped_object_key IS NULL;
