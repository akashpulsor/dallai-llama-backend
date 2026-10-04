-- Generation controls for one shot. A shot with no row follows its project's defaults
-- (project_config, V40); a row replaces them for that shot alone -- the whole set, so "this shot is
-- custom" is one fact and never a mix of inherited and overridden switches.
CREATE TABLE shot_generation_controls (
    shot_id                     UUID PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    project_id                  UUID        NOT NULL,
    fit_duration_to_dialogue    BOOLEAN     NOT NULL,
    auto_dub_dialogue           BOOLEAN     NOT NULL,
    mix_background_music        BOOLEAN     NOT NULL,
    prevent_duplicate_renders   BOOLEAN     NOT NULL,
    attach_previous_last_frame  BOOLEAN     NOT NULL,
    conform_to_planned_duration BOOLEAN     NOT NULL,
    interpolate_when_slowing    BOOLEAN     NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shot_generation_controls_project ON shot_generation_controls (project_id);
