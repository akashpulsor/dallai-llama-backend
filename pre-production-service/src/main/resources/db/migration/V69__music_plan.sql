-- One score per project, planned across the COMPLETE video.
--
-- Music was previously generated per shot (shot_background_music), which is why a thirty-second
-- film came back as six unrelated cues: every shot asked a music model for something on its own,
-- with a prompt derived from that shot's sound-design prose. This table holds the opposite --
-- a single musical identity and a contiguous timeline of sections whose boundaries follow the
-- story, not the cut.
--
-- shot_background_music is deliberately left alone. Per-shot music still works for anyone using
-- it, and nothing here removes or migrates it.
--
-- plan_json holds the structured MusicPlan (identity + sections). master_prompt is stored
-- alongside rather than derived on read so an edited prompt survives, but it is always
-- re-composable from plan_json -- the structure is the source of truth, the prose is a rendering.
CREATE TABLE IF NOT EXISTS music_plan (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    project_id             UUID NOT NULL,
    -- The film length the plan was built for. Generation asks for exactly this, and the validator
    -- refuses a plan whose last section ends anywhere else.
    total_duration_seconds NUMERIC(10, 2) NOT NULL,
    plan_json              TEXT NOT NULL,
    master_prompt          TEXT NOT NULL,
    ending_strategy        TEXT,
    -- Populated once the score has actually been generated; null means planned but not rendered.
    bucket                 VARCHAR(120),
    object_key             VARCHAR(512),
    -- Which model produced the audio, recorded because provider is configurable and "why does
    -- this project sound different" is otherwise unanswerable after the config moves on.
    generated_model_id     VARCHAR(160),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_music_plan_project UNIQUE (project_id)
);

CREATE INDEX IF NOT EXISTS idx_music_plan_tenant ON music_plan (tenant_id);
