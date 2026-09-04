-- The auto-dub mux step: lays one beat-matched cloned-voice audio track onto one silent video at
-- an offset. Verified against fal.ai's real, documented fal-ai/ffmpeg-api/merge-audio-video schema
-- (video_url, audio_url, optional start_offset in). Response envelope is unverified against this
-- specific app (falls through FalAiProvider's default {video: {url}} case, same as every other
-- fal.ai video-producing endpoint) -- confirm against a real call before relying on it in
-- production.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('fal-ai/ffmpeg-api/merge-audio-video', 'fal.ai', 'audio_video_merge', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/ffmpeg-api/merge-audio-video', 0, 0, 'USD', now());
