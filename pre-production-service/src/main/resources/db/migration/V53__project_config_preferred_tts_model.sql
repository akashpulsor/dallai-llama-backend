-- TTS model was hardcoded as a Java constant (ELEVENLABS_TTS_MODEL) in video-generation-service's
-- BeatDubbingService -- this makes it a real, project-selectable pick from llm-gateway's
-- model_master (type=tts), the same way preferred_video_model/preferred_voice_model already are.
ALTER TABLE project_config ADD COLUMN preferred_tts_model VARCHAR(128);
