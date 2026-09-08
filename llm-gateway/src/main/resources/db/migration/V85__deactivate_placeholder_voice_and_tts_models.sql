-- V15 seeded two fal.ai placeholders -- voice-clone-v1 and tts-v1 -- explicitly labeled as
-- "unblock ROUTING, not correctness of the actual provider call, which needs a real model chosen
-- and tested before it will work end to end" (see V15's own comment). They never got hooked up to
-- a real fal.ai endpoint, and the platform has since moved TTS + voice-clone off fal.ai to a
-- direct ElevenLabs adapter (V18 for elevenlabs-tts-v1, V55 for elevenlabs/instant-voice-clone,
-- both real, tested, and priced from ElevenLabs' actual per-character rate in V74). Leaving the
-- placeholders active let a project's preferredVoiceModel / preferredTtsModel pick a dead model
-- id whose CanonicalRequest would 502 at dispatch time -- deactivate them so only the real
-- adapters ever appear in the model picker.
UPDATE model_master SET status = 'inactive'
 WHERE model_id IN ('voice-clone-v1', 'tts-v1')
   AND provider_id = 'fal.ai';
