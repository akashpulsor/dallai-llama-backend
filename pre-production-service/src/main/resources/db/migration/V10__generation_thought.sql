-- No FK to shot: like critic-service's own critique_thought, this is an append-only event log,
-- not a strict relational child.
CREATE TABLE IF NOT EXISTS generation_thought (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL,
    step VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_generation_thought_shot ON generation_thought (shot_id, created_at ASC);
