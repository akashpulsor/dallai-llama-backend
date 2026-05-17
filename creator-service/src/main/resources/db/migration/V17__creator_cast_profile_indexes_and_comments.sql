CREATE INDEX IF NOT EXISTS idx_creator_profiles_tenant_user_updated
    ON creator_profiles (tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_profiles_project
    ON creator_profiles (project_id)
    WHERE project_id IS NOT NULL;

COMMENT ON TABLE creator_profiles IS 'Reusable cast/creator actor profiles available to the Creator UI cast planner.';
COMMENT ON COLUMN creator_profiles.id IS 'Primary key for one reusable cast profile.';
COMMENT ON COLUMN creator_profiles.tenant_id IS 'Tenant or organization that owns this cast profile.';
COMMENT ON COLUMN creator_profiles.user_id IS 'Creator user that owns this cast profile.';
COMMENT ON COLUMN creator_profiles.project_id IS 'Optional project where this cast profile was created; null means reusable across projects.';
COMMENT ON COLUMN creator_profiles.display_name IS 'Actor/cast display name shown in the cast picker.';
COMMENT ON COLUMN creator_profiles.role_in_short IS 'Default role for this person, such as Main Actor, Supporting Actor, Friend, Coach, or Narrator.';
COMMENT ON COLUMN creator_profiles.attributes IS 'Flexible JSONB actor details: age, gender, vibe/vibes, style, cameraConfidence, look, profile, notes, and future casting metadata.';
COMMENT ON COLUMN creator_profiles.confirmed IS 'True when the user has saved or confirmed this cast profile.';
COMMENT ON COLUMN creator_profiles.created_at IS 'Timestamp when the cast profile was created.';
COMMENT ON COLUMN creator_profiles.updated_at IS 'Timestamp when the cast profile was last updated.';
