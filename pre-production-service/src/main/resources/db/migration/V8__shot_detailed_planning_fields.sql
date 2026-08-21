-- Restores creator-service's real per-shot planning depth (creator_storyboard_scenes), lost in
-- the v1 slice's first pass -- expressed as typed columns here rather than that table's raw
-- jsonb maps, per this build's own no-maps rule.
ALTER TABLE shot
    ADD COLUMN camera_angle VARCHAR(160),
    ADD COLUMN camera_movement VARCHAR(160),
    ADD COLUMN lens_suggestion VARCHAR(160),
    ADD COLUMN fps INTEGER,
    ADD COLUMN composition TEXT,
    ADD COLUMN expression TEXT,
    ADD COLUMN emotion VARCHAR(240),
    ADD COLUMN body_language TEXT,
    ADD COLUMN action TEXT,
    ADD COLUMN voice_over TEXT,
    ADD COLUMN text_overlay TEXT,
    ADD COLUMN sound_design TEXT,
    ADD COLUMN editing_notes TEXT,
    ADD COLUMN retention_goal TEXT,
    ADD COLUMN creator_direction TEXT,
    ADD COLUMN subtitle_position VARCHAR(80),
    ADD COLUMN mobile_focus_area VARCHAR(120),
    ADD COLUMN safe_zone_notes TEXT,
    ADD COLUMN execution_difficulty VARCHAR(16),
    ADD COLUMN cinematic_execution TEXT,
    ADD COLUMN rookie_friendly_guide TEXT,
    ADD COLUMN sketch_prompt TEXT;
