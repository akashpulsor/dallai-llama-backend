-- Prerequisite for post-production-service's standalone dubbing flow (transcription) and
-- video-editing flow (video_edit) to route at all -- same "seed before it can dispatch" gap as
-- every model added so far.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES
    -- Long-running: a full video's transcription can take minutes, generous timeout on purpose.
    ('transcription-v1', 'fal.ai', 'transcription', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 300000),
    -- Video-to-video editing (Kling-style): a portion of a source video, a freeform edit
    -- instruction, and an optional reference image -- doc's "editing scenes through fal" note.
    ('kling-edit-v1', 'fal.ai', 'video_edit', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 180000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES
    ('transcription-v1', 0, 0, 'USD', now()),
    ('kling-edit-v1', 0, 0, 'USD', now());
