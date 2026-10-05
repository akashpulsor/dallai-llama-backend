-- Step-shot continuity (service/continuity): the user's per-shot choices that deliberately win over
-- the reference image, and the cached analysis of a step (reference image + shot inputs), so
-- toggling a choice re-resolves without paying for another vision call.
CREATE TABLE shot_continuity_override (
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    shot_id    UUID        NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    field      VARCHAR(32) NOT NULL,
    value      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_shot_continuity_override UNIQUE (shot_id, field)
);

CREATE TABLE step_continuity_analysis (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    shot_id        UUID        NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    source_shot_id UUID        NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    kind           VARCHAR(32) NOT NULL,
    input_hash     VARCHAR(64) NOT NULL,
    analysis_json  TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_step_continuity_analysis UNIQUE (shot_id, source_shot_id, kind)
);
