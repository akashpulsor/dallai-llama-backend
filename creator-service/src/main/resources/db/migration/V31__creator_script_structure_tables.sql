CREATE TABLE IF NOT EXISTS creator_script_beats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    script_id UUID NOT NULL REFERENCES creator_scripts(id) ON DELETE CASCADE,
    locked_idea_id UUID REFERENCES creator_ideas(id) ON DELETE SET NULL,
    story_idea_id UUID REFERENCES creator_ideas(id) ON DELETE CASCADE,
    beat_number INTEGER NOT NULL,
    title VARCHAR(220) NOT NULL,
    summary TEXT,
    character_focus VARCHAR(240),
    emotional_purpose TEXT,
    estimated_seconds INTEGER,
    beat_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_script_beat UNIQUE (script_id, beat_number)
);

CREATE INDEX IF NOT EXISTS idx_creator_script_beats_script
    ON creator_script_beats (script_id, beat_number);

CREATE INDEX IF NOT EXISTS idx_creator_script_beats_tenant_user
    ON creator_script_beats (tenant_id, user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS creator_script_characters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    script_id UUID NOT NULL REFERENCES creator_scripts(id) ON DELETE CASCADE,
    locked_idea_id UUID REFERENCES creator_ideas(id) ON DELETE SET NULL,
    story_idea_id UUID REFERENCES creator_ideas(id) ON DELETE CASCADE,
    character_key VARCHAR(160) NOT NULL,
    character_name VARCHAR(160) NOT NULL,
    character_role VARCHAR(120),
    gender VARCHAR(80),
    age VARCHAR(80),
    age_range VARCHAR(80),
    look TEXT,
    profile TEXT,
    persona TEXT,
    backstory TEXT,
    motivation TEXT,
    fear_or_block TEXT,
    relationship_to_story TEXT,
    speaking_style TEXT,
    visual_identity TEXT,
    character_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_script_character UNIQUE (script_id, character_key)
);

CREATE INDEX IF NOT EXISTS idx_creator_script_characters_script
    ON creator_script_characters (script_id, character_name);

CREATE INDEX IF NOT EXISTS idx_creator_script_characters_tenant_user
    ON creator_script_characters (tenant_id, user_id, created_at DESC);

ALTER TABLE creator_character_cast_mappings
    ADD COLUMN IF NOT EXISTS script_character_id UUID REFERENCES creator_script_characters(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_creator_character_cast_script_character
    ON creator_character_cast_mappings (script_character_id)
    WHERE script_character_id IS NOT NULL;

COMMENT ON TABLE creator_script_beats IS 'Normalized story beat rows for a generated creator script.';
COMMENT ON COLUMN creator_script_beats.script_id IS 'Generated script that owns this beat.';
COMMENT ON COLUMN creator_script_beats.beat_payload IS 'Original beat JSON snapshot for fields that are not first-class columns yet.';

COMMENT ON TABLE creator_script_characters IS 'Normalized generated character rows for a script. These can be mapped to reusable actors/cast profiles.';
COMMENT ON COLUMN creator_script_characters.script_id IS 'Generated script that owns this character.';
COMMENT ON COLUMN creator_script_characters.character_key IS 'Stable generated character key used to match UI mappings and regenerate-safe updates.';
COMMENT ON COLUMN creator_script_characters.character_payload IS 'Original character JSON snapshot for future fields.';

COMMENT ON COLUMN creator_character_cast_mappings.script_character_id IS
    'Optional normalized script character row that this cast mapping assigns to a reusable actor profile.';

COMMENT ON TABLE creator_profiles IS
    'Reusable actor/cast profile owned by a user. Rows can stay standalone or be linked to scripts through character cast mappings.';
