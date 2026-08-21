-- System prompts as data, not string literals in caller services (video-generation-service and
-- future callers). Append-only: never UPDATE content -- publish a new version and flip active.
CREATE TABLE prompt_template (
    template_id  BIGSERIAL PRIMARY KEY,
    task_key     VARCHAR(64) NOT NULL,
    version      INTEGER NOT NULL,
    content      TEXT NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_prompt_template_task_active ON prompt_template (task_key, active);
CREATE UNIQUE INDEX uq_prompt_template_task_active_one ON prompt_template (task_key) WHERE active = true;
