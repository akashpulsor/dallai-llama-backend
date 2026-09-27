-- fal.ai ACE-Step as a second, configurable music model alongside elevenlabs/music-v1.
--
-- Nothing about ElevenLabs changes. This is additive: a new row in the registry the gateway
-- already resolves through (model_master.provider_id -> ProviderRegistry -> adapter), so
-- switching a project's score between the two is a model id, not a code path. FalAiProvider
-- already handled type=music on the response side (audio.url); V115 pairs that with the request
-- shape ACE-Step actually documents.
--
-- Verified against fal.ai's published model page rather than assumed:
--   endpoint   fal-ai/ace-step, queue-based (the same submit -> poll -> fetch flow FalAiProvider
--              already runs for video)
--   input      tags (comma-separated style descriptors), lyrics (empty => instrumental),
--              duration (FLOAT SECONDS, default 60)
--   output     { audio: { url } }
--   price      $0.0002 per second of generated audio
--
-- timeout_ms is 180000 to match the provider's own queued default: a minute of music is a
-- meaningfully longer job than a TTS line, and the shared 60s music timeout used for ElevenLabs
-- would cut a long score off mid-render.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('fal-ai/ace-step', 'fal.ai', 'music', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 180000)
ON CONFLICT (model_id) DO NOTHING;

-- Duration-priced, exactly like elevenlabs/music-v1. LlmGatewayService.computeCost already bills
-- type=music off per_second_cost and already reads fal's `duration` param, so this needs no new
-- billing branch -- only a real rate. Leaving it unpriced would bill fal music at zero.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/ace-step', 0, 0, 'USD', now())
ON CONFLICT DO NOTHING;

UPDATE rate_card SET per_second_cost = 0.0002 WHERE model_id = 'fal-ai/ace-step';
