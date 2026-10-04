-- Bringing a freshly generated clip to its shot's planned length: slowed down (optionally with
-- frame interpolation) when it was generated shorter, trimmed when longer, with the dubbed line and
-- the music bed laid on at normal speed. ffmpeg's motion interpolation is CPU work, so this is a
-- queued request worked by ShotConformConsumer; the row is what the caller polls.
CREATE TABLE shot_clip_conform (
    request_id     UUID PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    project_id     UUID          NOT NULL,
    shot_id        UUID          NOT NULL,
    target_seconds NUMERIC(6, 3) NOT NULL,
    interpolate    BOOLEAN       NOT NULL,
    -- QUEUED, PROCESSING, COMPLETED, FAILED
    status         VARCHAR(16)   NOT NULL,
    -- The generation it conformed and the cut it produced (made the shot's active cut).
    source_job_id  UUID,
    version_id     UUID,
    error          TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

CREATE INDEX idx_shot_clip_conform_shot ON shot_clip_conform (shot_id, created_at DESC);
