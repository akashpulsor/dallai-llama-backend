-- Creative Direction: three alternative director's treatments for the locked idea, reviewed and
-- approved before any downstream stage (hook/beat, script, screenplay, shot list, camera and
-- lighting plans, production frames) is generated. The approved direction is the project's
-- creative contract and is passed alongside the idea into every one of those stages.
--
-- Every treatment field is its own column; nothing is stored as JSON. Additive only: existing
-- projects keep creative_direction_required = false and their legacy generation path.

-- New projects require an approved direction before dependent stages generate; projects created
-- before this release do not, and are never retro-gated.
ALTER TABLE project ADD COLUMN creative_direction_required BOOLEAN NOT NULL DEFAULT FALSE;

-- One row per "generate alternatives" press: the idea and brief exactly as they were sent to the
-- model, so a treatment can always be read against the input it was written for.
CREATE TABLE creative_direction_generation (
    id                    UUID        PRIMARY KEY,
    tenant_id             UUID        NOT NULL,
    project_id            UUID        NOT NULL REFERENCES project (id),
    round                 INT         NOT NULL,
    locked_idea_id        UUID,
    idea_title            TEXT,
    idea_concept          TEXT,
    idea_target_audience  TEXT,
    idea_campaign_angle   TEXT,
    idea_key_message      TEXT,
    idea_tone             TEXT,
    brief_text            TEXT,
    duration_seconds      INT,
    created_by            UUID,
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_creative_direction_generation_round UNIQUE (project_id, round)
);

-- One director's treatment. A revision is a new row (version + 1, revised_from_id -> the row it
-- revises); an approved row is never edited, and at most one row per project is APPROVED.
CREATE TABLE creative_direction (
    id                          UUID        PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    project_id                  UUID        NOT NULL REFERENCES project (id),
    generation_id               UUID        NOT NULL REFERENCES creative_direction_generation (id),
    option_number               INT         NOT NULL,
    version                     INT         NOT NULL,
    revised_from_id             UUID        REFERENCES creative_direction (id),
    title                       TEXT        NOT NULL,
    creative_concept            TEXT        NOT NULL,
    directors_treatment         TEXT        NOT NULL,
    storytelling_style          TEXT,
    story_period                TEXT,
    color_treatment             TEXT,
    contrast                    TEXT,
    texture                     TEXT,
    overall_aesthetic           TEXT,
    cinematography_philosophy   TEXT,
    emotional_journey           TEXT,
    sound_direction             TEXT,
    signature_creative_device   TEXT,
    creative_rationale          TEXT,
    recommended                 BOOLEAN     NOT NULL,
    recommendation_reason       TEXT,
    review_status               VARCHAR(24) NOT NULL,
    approved_at                 TIMESTAMPTZ,
    approved_by                 UUID,
    approved_via                VARCHAR(16),
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_creative_direction_project ON creative_direction (project_id);
CREATE INDEX idx_creative_direction_generation ON creative_direction (generation_id);
CREATE UNIQUE INDEX uq_creative_direction_one_approved ON creative_direction (project_id)
    WHERE review_status = 'APPROVED';

-- A client reference a treatment draws on. asset_id is the original row id of the client's upload
-- in creative-planning-service (project_reference_image / project_reference_video) -- the media
-- itself is never copied; bucket/object_key point at the same stored object. The client's
-- instruction and the reference analysis are captured as they stood when the treatment was written.
CREATE TABLE creative_direction_reference (
    id                     UUID        PRIMARY KEY,
    creative_direction_id  UUID        NOT NULL REFERENCES creative_direction (id),
    asset_id               UUID        NOT NULL,
    media_type             VARCHAR(8)  NOT NULL,
    bucket                 VARCHAR(255),
    object_key             TEXT,
    client_instruction     TEXT,
    reference_analysis     TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_creative_direction_reference UNIQUE (creative_direction_id, asset_id)
);

-- Review notes on a treatment, from the creator or from the client through the review link.
CREATE TABLE creative_direction_feedback (
    id                     UUID        PRIMARY KEY,
    creative_direction_id  UUID        NOT NULL REFERENCES creative_direction (id),
    source                 VARCHAR(16) NOT NULL,
    author_user_id         UUID,
    feedback               TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_creative_direction_feedback_direction ON creative_direction_feedback (creative_direction_id);

-- Which approved direction a script / screenplay version was generated from (null = before
-- Creative Direction existed, or a legacy project). Compared with the currently approved
-- direction to show "generated from an earlier direction" -- content is never regenerated or
-- deleted automatically.
ALTER TABLE script ADD COLUMN creative_direction_id UUID;
ALTER TABLE screenplay ADD COLUMN creative_direction_id UUID;
