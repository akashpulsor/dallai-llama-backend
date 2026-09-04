-- Moved from video-generation-service (was V1/V8 there) as part of the prompt-formatting
-- ownership move: how each model wants its prompt shaped is llm-gateway's concern now
-- (see PromptFormatController + service/prompt/*). Video-gen's own table stays historically
-- applied but becomes orphan; only llm-gateway reads/writes this one going forward.
CREATE TABLE negative_prompt_library (
    snippet_id   BIGSERIAL PRIMARY KEY,
    scope        VARCHAR(16) NOT NULL,   -- BASE / PROVIDER / PROJECT / ISSUE
    provider_id  VARCHAR(64),
    content      TEXT NOT NULL,
    tags         TEXT[] NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_negative_prompt_library_scope_provider ON negative_prompt_library (scope, provider_id);

-- Seed data (same content as video-gen V8 -- shared base library of common visual artifacts).
INSERT INTO negative_prompt_library (scope, provider_id, content, tags) VALUES
('BASE', NULL, 'extra fingers, malformed hands, warped face, distorted anatomy', ARRAY['anatomy']),
('BASE', NULL, 'blurry, low quality, oversaturated, motion blur artifacts', ARRAY['quality']),
('BASE', NULL, 'watermark, logo (if not brand), text (if not intended)', ARRAY['overlay']),
('BASE', NULL, 'duplicate faces, floating limbs, mangled features', ARRAY['anatomy']);

-- Seedance-specific -- over-warps hands on close-ups.
INSERT INTO negative_prompt_library (scope, provider_id, content, tags) VALUES
('PROVIDER', 'fal.ai', 'deformed hands, extra fingers, mutated hands', ARRAY['anatomy', 'seedance']);
