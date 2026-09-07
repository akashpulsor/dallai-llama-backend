-- TTS model was hardcoded (BeatDubbingService.ELEVENLABS_TTS_MODEL) -- captured at generate()
-- time like voice_clone_model/resolution above, forwarded to BeatDubbingService.dub() at approve().
ALTER TABLE video_gen_job ADD COLUMN tts_model VARCHAR(128);
