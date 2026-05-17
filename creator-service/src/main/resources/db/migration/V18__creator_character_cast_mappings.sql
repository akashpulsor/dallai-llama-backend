CREATE TABLE IF NOT EXISTS creator_character_cast_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    locked_idea_id UUID NOT NULL REFERENCES creator_ideas(id) ON DELETE CASCADE,
    story_idea_id UUID NOT NULL REFERENCES creator_ideas(id) ON DELETE CASCADE,
    script_id UUID REFERENCES creator_scripts(id) ON DELETE SET NULL,
    character_key VARCHAR(160) NOT NULL,
    character_name VARCHAR(160) NOT NULL,
    character_role VARCHAR(120),
    cast_profile_id UUID REFERENCES creator_profiles(id) ON DELETE SET NULL,
    cast_display_name VARCHAR(160),
    character_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    cast_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_character_cast_mapping UNIQUE (tenant_id, user_id, locked_idea_id, story_idea_id, character_key)
);

CREATE INDEX IF NOT EXISTS idx_creator_character_cast_story
    ON creator_character_cast_mappings (tenant_id, user_id, locked_idea_id, story_idea_id, created_at);

CREATE INDEX IF NOT EXISTS idx_creator_character_cast_profile
    ON creator_character_cast_mappings (cast_profile_id)
    WHERE cast_profile_id IS NOT NULL;

COMMENT ON TABLE creator_character_cast_mappings IS 'Maps generated story/script characters to saved creator cast profiles for a production workflow.';
COMMENT ON COLUMN creator_character_cast_mappings.id IS 'Primary key for one character-to-cast assignment.';
COMMENT ON COLUMN creator_character_cast_mappings.tenant_id IS 'Tenant or organization that owns this mapping.';
COMMENT ON COLUMN creator_character_cast_mappings.user_id IS 'Creator user that owns this mapping.';
COMMENT ON COLUMN creator_character_cast_mappings.project_id IS 'Optional active project for the production workflow.';
COMMENT ON COLUMN creator_character_cast_mappings.locked_idea_id IS 'Locked creative brief that owns this mapping.';
COMMENT ON COLUMN creator_character_cast_mappings.story_idea_id IS 'Saved story idea whose characters are being assigned to cast.';
COMMENT ON COLUMN creator_character_cast_mappings.script_id IS 'Optional generated screenplay id if the mapping was saved after screenplay generation.';
COMMENT ON COLUMN creator_character_cast_mappings.character_key IS 'Stable generated character key used for upsert from the UI.';
COMMENT ON COLUMN creator_character_cast_mappings.character_name IS 'Generated story character name.';
COMMENT ON COLUMN creator_character_cast_mappings.character_role IS 'Generated story role, such as Main creator or Reaction character.';
COMMENT ON COLUMN creator_character_cast_mappings.cast_profile_id IS 'Saved creator profile selected to play this character.';
COMMENT ON COLUMN creator_character_cast_mappings.cast_display_name IS 'Snapshot of selected cast profile display name.';
COMMENT ON COLUMN creator_character_cast_mappings.character_payload IS 'Snapshot of character gender, age, look, profile, persona, and story metadata at mapping time.';
COMMENT ON COLUMN creator_character_cast_mappings.cast_payload IS 'Snapshot of selected cast age, gender, look, profile, vibe, camera comfort, and role details.';
COMMENT ON COLUMN creator_character_cast_mappings.created_at IS 'Timestamp when the mapping was created.';
COMMENT ON COLUMN creator_character_cast_mappings.updated_at IS 'Timestamp when the mapping was last updated.';
