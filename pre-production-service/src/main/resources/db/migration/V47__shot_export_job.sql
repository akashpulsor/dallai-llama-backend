-- Persistent receipt for every shot-export-PDF the creator generated. Written right after the
-- compressed PDF lands in MinIO, so history survives the signed URL's one-hour expiry (the row
-- can always re-sign from bucket + object_key). Not a workflow job -- the export completes
-- synchronously in the same request that inserts the row; this is a paper trail, not a queue.
CREATE TABLE shot_export_job (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    created_by UUID,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    shot_count INTEGER NOT NULL,
    file_size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL
);

-- Serves the export-history panel: "recent exports for this project", newest first.
CREATE INDEX idx_shot_export_job_project_created ON shot_export_job (project_id, created_at DESC);
-- Tenant-scoped listings and the tenant guard on the download-by-id endpoint.
CREATE INDEX idx_shot_export_job_tenant ON shot_export_job (tenant_id, created_at DESC);
