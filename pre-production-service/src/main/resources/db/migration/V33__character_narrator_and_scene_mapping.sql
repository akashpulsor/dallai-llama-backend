-- Two real gaps found while building out characterization: (1) script_character had no place for
-- complexion, a casting-relevant detail creator-service's real character prompt already asked
-- for; (2) screenplay_scene.character_focus was always free text, never a real reference to the
-- character(s) actually in that scene -- fine for display, useless for "which characters appear
-- in this scene" (a scene with none is a pure motion-graphic/B-roll beat, not an oversight).

ALTER TABLE script_character ADD COLUMN complexion VARCHAR(80);

CREATE TABLE screenplay_scene_character (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    screenplay_scene_id UUID NOT NULL REFERENCES screenplay_scene(id) ON DELETE CASCADE,
    script_character_id UUID NOT NULL REFERENCES script_character(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_scene_character UNIQUE (screenplay_scene_id, script_character_id)
);

CREATE INDEX idx_scene_character_scene ON screenplay_scene_character(screenplay_scene_id);
CREATE INDEX idx_scene_character_character ON screenplay_scene_character(script_character_id);
