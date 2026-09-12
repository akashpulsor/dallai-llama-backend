ALTER TABLE cast_profile ADD COLUMN cloned_voice_id VARCHAR(128);
ALTER TABLE cast_profile ADD COLUMN cloned_voice_provider_id VARCHAR(64);
ALTER TABLE cast_profile ADD COLUMN voice_identity_type VARCHAR(16);

-- Keep pre-existing actor profiles semantically complete when this migration is deployed. A
-- reference sample represents a human voice; a selected built-in provider voice represents AI.
UPDATE cast_profile
SET voice_identity_type = CASE
    WHEN profile_type = 'ACTOR'
         AND voice_ref_bucket IS NOT NULL
         AND voice_ref_object_key IS NOT NULL THEN 'HUMAN'
    WHEN profile_type = 'ACTOR'
         AND builtin_voice_id IS NOT NULL THEN 'AI'
    ELSE NULL
END
WHERE voice_identity_type IS NULL;

ALTER TABLE cast_profile ADD CONSTRAINT cast_profile_cloned_voice_identity_check CHECK (
    (cloned_voice_id IS NULL AND cloned_voice_provider_id IS NULL)
    OR (cloned_voice_id IS NOT NULL AND cloned_voice_provider_id IS NOT NULL AND voice_identity_type IS NOT NULL)
);
ALTER TABLE cast_profile ADD CONSTRAINT cast_profile_voice_identity_type_check CHECK (
    voice_identity_type IS NULL OR voice_identity_type IN ('HUMAN', 'AI')
);