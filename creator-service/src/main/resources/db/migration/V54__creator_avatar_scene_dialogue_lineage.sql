CREATE TABLE IF NOT EXISTS creator_avatar_scene_dialogues (
    id UUID PRIMARY KEY,
    root_dialogue_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID,
    video_run_id UUID NOT NULL,
    script_id UUID NOT NULL,
    script_shot_id UUID,
    scene_number INTEGER NOT NULL,
    shot_number INTEGER NOT NULL,
    sequence_number INTEGER NOT NULL,
    speaker VARCHAR(128),
    dialogue_role VARCHAR(64) NOT NULL DEFAULT 'spoken_dialogue',
    language VARCHAR(64) NOT NULL,
    language_key VARCHAR(64) NOT NULL,
    language_code VARCHAR(24),
    dialogue_text TEXT NOT NULL,
    source_kind VARCHAR(48) NOT NULL,
    source_path VARCHAR(320) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    dialogue_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    screenplay_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    translation_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    translation_provider VARCHAR(80),
    translation_model VARCHAR(160),
    translation_prompt_run_id UUID,
    is_source BOOLEAN NOT NULL DEFAULT FALSE,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version_number INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_creator_avatar_dialogue_root
        FOREIGN KEY (root_dialogue_id)
        REFERENCES creator_avatar_scene_dialogues(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_creator_avatar_dialogue_current_language
    ON creator_avatar_scene_dialogues (
        tenant_id,
        user_id,
        video_run_id,
        scene_number,
        language_key
    )
    WHERE is_current;

CREATE INDEX IF NOT EXISTS idx_creator_avatar_dialogue_run_scene
    ON creator_avatar_scene_dialogues (
        tenant_id,
        user_id,
        video_run_id,
        scene_number,
        is_current
    );

CREATE INDEX IF NOT EXISTS idx_creator_avatar_dialogue_script
    ON creator_avatar_scene_dialogues (script_id, shot_number, is_current);

CREATE INDEX IF NOT EXISTS idx_creator_avatar_dialogue_root_language
    ON creator_avatar_scene_dialogues (root_dialogue_id, language_key, is_current);

CREATE INDEX IF NOT EXISTS idx_creator_avatar_dialogue_training
    ON creator_avatar_scene_dialogues (language_key, speaker, created_at);
