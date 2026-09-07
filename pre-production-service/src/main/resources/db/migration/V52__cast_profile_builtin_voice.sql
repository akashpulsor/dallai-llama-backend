-- Alternative to a cloned voice sample: an ACTOR profile with no recorded voice can pick a stock
-- ElevenLabs voice instead (see llm-gateway's builtin_voice master table, V80 there). No FK across
-- services -- same "hand over a resolved value, don't cross-service-FK it" convention already used
-- for every other provider/model_id string this service stores. Mutually exclusive with
-- voice_ref_bucket/voice_ref_object_key in practice (service layer enforces it), not by constraint,
-- same as those two columns aren't constrained against each other either.
ALTER TABLE cast_profile ADD COLUMN builtin_voice_id VARCHAR(128);

-- MiniMax voice-clone is being deactivated (llm-gateway V79) in favor of ElevenLabs-only cloning.
-- Any project still pinned to it would 400 at next dispatch; clearing the pin lets it fall back to
-- video-generation-service's new default (ElevenLabs) cleanly instead.
UPDATE project_config SET preferred_voice_model = NULL WHERE preferred_voice_model = 'fal-ai/minimax/voice-clone';
