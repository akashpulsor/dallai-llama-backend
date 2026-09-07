-- A beat now carries a stock ElevenLabs voice_id instead of a cloned reference-audio URL when the
-- speaking character has no actor voice sample -- see pre-production-service's
-- CastProfile.builtinVoiceId and V80's builtin_voice master table in llm-gateway. Nullable and
-- independent of voice_reference_url: exactly one of the two is set per beat with a usable voice.
ALTER TABLE video_gen_job_dialogue_beat ADD COLUMN builtin_voice_id VARCHAR(128);
