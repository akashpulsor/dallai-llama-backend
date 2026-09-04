-- Null = no auto-dub was attempted (mute_audio was false); true = the beat-matched cloned-voice
-- mux completed; false = mute_audio was true but the dub step failed, so the job's output is the
-- plain silent video (see ShotGenerationOrchestrator.approve()'s catch around BeatDubbingService).
-- post-production-service reads this to decide whether DialogueSyncCoordinator can skip straight
-- to a quality check instead of running the full clone/synthesize/lip-sync pipeline.
ALTER TABLE video_gen_job ADD COLUMN dub_succeeded BOOLEAN;
