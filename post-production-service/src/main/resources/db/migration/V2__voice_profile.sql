CREATE TABLE voice_profile (
    voice_profile_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    project_id        UUID NOT NULL,
    character_ref     VARCHAR(128) NOT NULL,
    language          VARCHAR(16) NOT NULL,
    provider_id       VARCHAR(64) NOT NULL,
    provider_voice_id VARCHAR(256) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One clone per (tenant, project, character, language) -- the whole point of this table is to
-- never re-clone the same character's voice twice for the same target language.
CREATE UNIQUE INDEX uq_voice_profile_tenant_project_character_language
    ON voice_profile (tenant_id, project_id, character_ref, language);
