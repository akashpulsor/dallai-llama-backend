-- What a video model can actually be asked for, per model.
--
-- Durations used to be two global properties (3-10s) applied to every model, which is how a Wan
-- shot was dispatched at 1s and refused by fal.ai ("duration should be greater than or equal to
-- 2"), and how Seedance -- which takes 4-15s -- was capped at 10. Frame rate was worse: it was
-- written onto jobs as if it were chosen, but neither model takes a frame rate at all. Each renders
-- at its own native rate, so the only honest options list is that one rate.
CREATE TABLE video_model_generation_capability (
    model_id             VARCHAR(128) PRIMARY KEY,
    min_duration_seconds INTEGER      NOT NULL CHECK (min_duration_seconds > 0),
    max_duration_seconds INTEGER      NOT NULL,
    -- Where these numbers came from, so the next person can re-check them rather than trust them.
    source_note          TEXT,
    CHECK (max_duration_seconds >= min_duration_seconds)
);

CREATE TABLE video_model_frame_rate (
    id       BIGSERIAL PRIMARY KEY,
    model_id VARCHAR(128) NOT NULL REFERENCES video_model_generation_capability (model_id) ON DELETE CASCADE,
    fps      INTEGER      NOT NULL CHECK (fps > 0),
    CONSTRAINT uq_video_model_frame_rate UNIQUE (model_id, fps)
);

INSERT INTO video_model_generation_capability (model_id, min_duration_seconds, max_duration_seconds, source_note) VALUES
('bytedance/seedance-2.0/fast', 4, 15,
 'fal.ai bytedance/seedance-2.0/fast schema: duration enum auto,4..15. No frame-rate input; renders at 24fps.'),
('alibaba/wan-3.0-prime', 2, 10,
 'Minimum 2 from fal.ai''s own 422 (duration >= 2). The schema documents no maximum; 10 is the platform cap the service used before. No frame-rate input; clips probe at 30fps.');

INSERT INTO video_model_frame_rate (model_id, fps) VALUES
('bytedance/seedance-2.0/fast', 24),
('alibaba/wan-3.0-prime', 30);

-- One shot's path from approved plan to a submitted source clip: the required actions, the
-- duration/FPS assessment, the creator's chosen settings, the second-by-second timeline, and the
-- prompt in its three forms (AI recommendation, creator's draft, and -- on shot_prompt -- what was
-- actually submitted). One row per shot; re-analysing replaces its children.
--
-- The *_duration_seconds / *_fps pairs on the timeline and the prompt record the settings each was
-- built for. They are compared with the selected settings by the page, which is how a recommendation
-- built for 8s is shown stale once the creator picks 6s -- nothing here computes "stale" itself.
CREATE TABLE shot_generation_plan (
    plan_id                          UUID PRIMARY KEY,
    tenant_id                        UUID         NOT NULL,
    project_id                       UUID         NOT NULL,
    shot_id                          UUID         NOT NULL,
    shot_ref                         VARCHAR(64),
    model_id                         VARCHAR(128) NOT NULL,
    planned_duration_seconds         NUMERIC(6, 3),

    assessed_at                      TIMESTAMPTZ,
    shorter_generation_suitable      BOOLEAN,
    minimum_viable_duration_seconds  NUMERIC(6, 3),
    recommended_duration_seconds     INTEGER,
    recommended_fps                  INTEGER,
    assessment_reasoning             TEXT,

    generation_duration_seconds      INTEGER,
    generation_fps                   INTEGER,

    timeline_duration_seconds        INTEGER,
    timeline_fps                     INTEGER,
    timeline_built_at                TIMESTAMPTZ,

    ai_recommended_prompt            TEXT,
    prompt_duration_seconds          INTEGER,
    prompt_fps                       INTEGER,
    prompt_continuation_object_key   VARCHAR(1024),
    prompt_composed_at               TIMESTAMPTZ,

    -- Null until the creator saves an edit; "reset to recommendation" sets it back to null.
    user_edited_prompt               TEXT,
    draft_revision                   INTEGER      NOT NULL DEFAULT 0,
    draft_saved_at                   TIMESTAMPTZ,

    -- The previous shot's last frame, attached so this clip opens exactly where that one ended.
    continuation_source_shot_id      UUID,
    continuation_frame_bucket        VARCHAR(128),
    continuation_frame_object_key    VARCHAR(1024),
    continuation_frame_timestamp_ms  BIGINT,
    -- The frame is taken by post-production off the request thread. While it is being taken the
    -- request id is set and the frame columns are empty; a failure leaves its reason here.
    continuation_request_id          UUID,
    continuation_error               TEXT,

    submitted_job_id                 UUID,
    submitted_at                     TIMESTAMPTZ,

    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_shot_generation_plan_shot UNIQUE (tenant_id, shot_id)
);

-- The actions the shot plan requires, extracted deterministically and numbered once, so every
-- later step (assessment, timeline, prompt review) is checked against the same identifiers.
CREATE TABLE shot_generation_plan_action (
    id                   BIGSERIAL PRIMARY KEY,
    plan_id              UUID         NOT NULL REFERENCES shot_generation_plan (plan_id) ON DELETE CASCADE,
    ordinal              INTEGER      NOT NULL,
    action_id            VARCHAR(16)  NOT NULL,
    -- OPENING_STATE, ACTION, DIALOGUE, CAMERA, ENDING_STATE
    kind                 VARCHAR(24)  NOT NULL,
    description          TEXT         NOT NULL,
    -- Seconds a FIXED action needs in full (a measured line of dialogue). Null = flexible timing.
    fixed_seconds        NUMERIC(6, 3),
    depends_on_action_id VARCHAR(16),
    CONSTRAINT uq_shot_generation_plan_action UNIQUE (plan_id, action_id)
);

-- The assessment's claimed timing per action. Stored as the model answered, unknown ids included,
-- so the page can show exactly what failed validation.
CREATE TABLE shot_generation_plan_coverage (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       UUID          NOT NULL REFERENCES shot_generation_plan (plan_id) ON DELETE CASCADE,
    ordinal       INTEGER       NOT NULL,
    action_id     VARCHAR(64)   NOT NULL,
    start_seconds NUMERIC(6, 3) NOT NULL,
    end_seconds   NUMERIC(6, 3) NOT NULL,
    preserved     BOOLEAN       NOT NULL
);

CREATE TABLE shot_generation_plan_risk (
    id      BIGSERIAL PRIMARY KEY,
    plan_id UUID    NOT NULL REFERENCES shot_generation_plan (plan_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    risk    TEXT    NOT NULL
);

-- The second-by-second source timeline for the selected generation duration.
CREATE TABLE shot_generation_plan_interval (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         UUID          NOT NULL REFERENCES shot_generation_plan (plan_id) ON DELETE CASCADE,
    ordinal         INTEGER       NOT NULL,
    start_seconds   NUMERIC(6, 3) NOT NULL,
    end_seconds     NUMERIC(6, 3) NOT NULL,
    action_id       VARCHAR(64)   NOT NULL,
    action          TEXT          NOT NULL,
    subject_state   TEXT,
    camera_behavior TEXT,
    hold_required   BOOLEAN       NOT NULL DEFAULT false
);

CREATE INDEX idx_shot_generation_plan_action_plan ON shot_generation_plan_action (plan_id, ordinal);
CREATE INDEX idx_shot_generation_plan_coverage_plan ON shot_generation_plan_coverage (plan_id, ordinal);
CREATE INDEX idx_shot_generation_plan_risk_plan ON shot_generation_plan_risk (plan_id, ordinal);
CREATE INDEX idx_shot_generation_plan_interval_plan ON shot_generation_plan_interval (plan_id, ordinal);

-- Generation controls: every step of the render path that can stop or reshape a shot is a
-- per-project switch, and the defaults are the path that lets a shot be generated.
--   fit_duration_to_dialogue   resize the clip to its measured dialogue, and refuse a line that cannot
--                              fit (the refusals that failed jobs in production). Off: the clip is
--                              generated at exactly the duration chosen.
--   auto_dub_dialogue          generate silent and lay the cloned voice on afterwards. Off: the model
--                              performs the line itself.
--   mix_background_music       lay the shot's music bed under the finished clip.
--   prevent_duplicate_renders  refuse to queue a shot that is already rendering, which would bill it twice.
--   attach_previous_last_frame start every shot from the previous shot's last frame when it exists.
--   conform_to_planned_duration a clip generated at another length is brought to the planned length in
--                              post-production -- slowed when shorter, trimmed when longer.
--   interpolate_when_slowing   synthesise in-between frames when slowing, so motion stays smooth.
ALTER TABLE project_config
    ADD COLUMN fit_duration_to_dialogue   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN auto_dub_dialogue          BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN mix_background_music       BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN prevent_duplicate_renders  BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN attach_previous_last_frame BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN conform_to_planned_duration BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN interpolate_when_slowing   BOOLEAN NOT NULL DEFAULT true;

-- The shot's planned length beside the length actually rendered, and the post-production request
-- that conforms one to the other.
ALTER TABLE video_gen_job
    ADD COLUMN planned_duration_seconds INTEGER,
    ADD COLUMN conform_request_id       UUID;
