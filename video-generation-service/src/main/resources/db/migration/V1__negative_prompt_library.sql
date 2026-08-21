CREATE TABLE negative_prompt_library (
    snippet_id   BIGSERIAL PRIMARY KEY,
    scope        VARCHAR(16) NOT NULL, -- BASE / PROVIDER / PROJECT / ISSUE
    provider_id  VARCHAR(64),
    content      TEXT NOT NULL,
    tags         TEXT[] NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_negative_prompt_library_scope_provider ON negative_prompt_library (scope, provider_id);
