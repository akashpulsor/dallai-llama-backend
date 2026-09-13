-- A shot's foley cue sheet -- the sounds the shot makes and when, derived once when the shot is
-- planned rather than every time it is prepared for generation.
--
-- These used to be derived inside video-generation-service's prepare, which meant a paid LLM
-- round-trip per shot on every prepare for a cue sheet that never changes between prepares: it
-- describes the shot's sound design, which is a property of the shot, not of one prompt version.
-- Owning them here puts them alongside the rest of the shot plan (camera plan, lighting plan,
-- dialogue beats) and lets the prepare bundle carry them, so prepare just reads them.
CREATE TABLE shot_foley_cue (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    shot_id       UUID NOT NULL REFERENCES shot (id) ON DELETE CASCADE,
    timestamp_ms  INTEGER NOT NULL,
    cue_type      VARCHAR(32) NOT NULL,
    description   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The bundle reads every cue for a shot in playback order; nothing looks one up by id.
CREATE INDEX idx_shot_foley_cue_shot ON shot_foley_cue (shot_id, timestamp_ms);
