-- Project-level pick from llm-gateway's real model_master (type=voice_clone) -- null keeps
-- BeatDubbingService's own configured default. Same "master data, not a hardcoded list"
-- precedent as pre-production-service's Project.lockedIdeaId-style preferences.
ALTER TABLE project_config ADD COLUMN preferred_voice_clone_model VARCHAR(160);
