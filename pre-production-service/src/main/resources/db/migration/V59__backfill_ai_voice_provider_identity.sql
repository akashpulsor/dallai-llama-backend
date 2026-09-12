-- V58 introduced the provider-qualified voice identity. Legacy built-in voices are ElevenLabs
-- providerVoiceIds, so populate the pair before new code begins reading it preferentially.
UPDATE cast_profile
SET cloned_voice_id = builtin_voice_id,
    cloned_voice_provider_id = 'elevenlabs',
    voice_identity_type = 'AI'
WHERE builtin_voice_id IS NOT NULL
  AND builtin_voice_id <> ''
  AND cloned_voice_id IS NULL
  AND cloned_voice_provider_id IS NULL;