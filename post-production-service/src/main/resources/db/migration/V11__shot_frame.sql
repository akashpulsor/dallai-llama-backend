-- Frames taken from a shot's clip: the first, the last, a sample, or every frame.
--
-- Keyed by the clip VERSION they came from, not just the shot. A shot's clip is replaced whenever a
-- cut is accepted, and a last frame taken from the clip it replaced is a frame the audience never
-- sees -- continuing the next shot from it would put a seam exactly where there should not be one.
CREATE TABLE shot_frame (
    frame_id        UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    project_id      UUID         NOT NULL,
    shot_id         UUID         NOT NULL,
    clip_version_id UUID         NOT NULL,
    -- FIRST_FRAME, LAST_FRAME, SAMPLE, ALL
    mode            VARCHAR(16)  NOT NULL,
    -- Index of the frame in the source clip, from 0. Null when the clip's frame rate is unknown.
    frame_number    BIGINT,
    timestamp_ms    BIGINT       NOT NULL,
    bucket          VARCHAR(128) NOT NULL,
    object_key      VARCHAR(512) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_shot_frame_version_mode ON shot_frame (shot_id, clip_version_id, mode, timestamp_ms);
