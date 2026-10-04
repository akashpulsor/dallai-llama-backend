-- Follows V11 (already applied -- never edit an applied migration).
--
-- One request to take frames, worked off the request thread by FrameExtractionConsumer. Extraction
-- downloads a clip and runs ffmpeg; doing that inside HTTP requests let any number run at once in
-- one pod. The consumer group is the concurrency limit, and this row is what the caller polls.
CREATE TABLE shot_frame_extraction (
    request_id      UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    project_id      UUID         NOT NULL,
    shot_id         UUID         NOT NULL,
    mode            VARCHAR(16)  NOT NULL,
    sample_fps      INTEGER,
    -- QUEUED, PROCESSING, COMPLETED, FAILED
    status          VARCHAR(16)  NOT NULL,
    -- The cut the frames were taken from, set when the work runs.
    clip_version_id UUID,
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_shot_frame_extraction_shot ON shot_frame_extraction (shot_id, created_at DESC);
