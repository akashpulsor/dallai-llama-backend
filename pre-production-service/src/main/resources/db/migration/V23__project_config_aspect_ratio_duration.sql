ALTER TABLE project_config ADD COLUMN aspect_ratio VARCHAR(16);
ALTER TABLE project_config ADD COLUMN target_duration_seconds INTEGER;
ALTER TABLE project_config ADD COLUMN prefer_motion_graphics BOOLEAN NOT NULL DEFAULT FALSE;
