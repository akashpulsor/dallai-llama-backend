ALTER TABLE script
    ADD COLUMN pacing_style VARCHAR(240),
    ADD COLUMN emotional_arc TEXT,
    ADD COLUMN hook_strategy TEXT;

ALTER TABLE screenplay_scene
    ADD COLUMN character_focus VARCHAR(240),
    ADD COLUMN emotional_purpose TEXT;
