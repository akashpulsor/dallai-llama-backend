-- Closes the real-cost gap for ElevenLabs (called directly, not through fal.ai) -- every row here
-- was previously a documented $0 placeholder ("real cost is subscription-tier-based, not a clean
-- per-call rate", V18/V55/V56); this replaces that with ElevenLabs' actual pay-as-you-go API
-- pricing, live since May 2026, which decouples the real per-unit rate from whichever legacy
-- subscription tier is active.
--
-- elevenlabs-tts-v1 (type=tts): $0.10 per 1000 characters for eleven_multilingual_v2 (the exact
-- model ElevenLabsProvider hardcodes) -- verified against elevenlabs.io/pricing's PAYG rates.
-- ElevenLabsProvider.generate now reports real character count as inputTokens, so
-- LlmGatewayService.computeCost's existing generic input-token-cost path bills this correctly
-- with zero new billing logic -- input_token_cost is $/character, not $/token, but the formula
-- (inputTokenCost * inputTokens) is the same arithmetic either way.
UPDATE rate_card SET input_token_cost = 0.0001 WHERE model_id = 'elevenlabs-tts-v1';

-- elevenlabs/instant-voice-clone (type=voice_clone): cloning itself draws no ElevenLabs credits
-- (confirmed against their pricing page -- it's not in the per-minute/per-character cost table).
-- Charged anyway as a deliberate revenue line, not a discovered cost: ElevenLabsProvider.cloneVoice
-- now reports a flat 1000-character-equivalent, billed at the SAME real per-character rate as TTS
-- above ($0.10/1000 chars) so the number stays tied to a real published rate. See
-- ElevenLabsProvider.FLAT_CLONE_CHARGE_CHAR_EQUIVALENT for the exact mechanism.
UPDATE rate_card SET input_token_cost = 0.0001 WHERE model_id = 'elevenlabs/instant-voice-clone';

-- elevenlabs/music-v1 (type=music): ElevenLabs' credit system prices music at 900 credits/minute
-- (elevenlabs.io/pricing); PAYG's $0.10/1000-character rate implies $0.0001/credit for
-- eleven_multilingual_v2-equivalent usage (1 char = 1 credit there), so 900 * 0.0001 / 60 =
-- $0.0015/s is a real-rate-derived per-second cost, not an invented one. LlmGatewayService.
-- computeCost's duration-priced branch now includes type=music (previously only video/upscale),
-- and its duration lookup now also reads ElevenLabsProvider.composeMusic's real music_length_ms
-- param (ms, converted to seconds) alongside fal.ai's duration_seconds/duration.
UPDATE rate_card SET per_second_cost = 0.0015 WHERE model_id = 'elevenlabs/music-v1';
