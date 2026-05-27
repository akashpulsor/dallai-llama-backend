CREATE INDEX IF NOT EXISTS idx_creator_ideas_tenant_user_updated
    ON creator_ideas (tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_ideas_project_tenant_user_updated
    ON creator_ideas (project_id, tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_ideas_tenant_user_status_updated
    ON creator_ideas (tenant_id, user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_ideas_selection_context_gin
    ON creator_ideas USING GIN (selection_context);

CREATE INDEX IF NOT EXISTS idx_creator_ideas_storyline_history
    ON creator_ideas (tenant_id, user_id, updated_at DESC)
    WHERE coalesce(script, '') <> '' OR jsonb_exists(selection_context, 'storyScript');

CREATE INDEX IF NOT EXISTS idx_creator_scripts_tenant_user_updated
    ON creator_scripts (tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_scripts_project_tenant_user_updated
    ON creator_scripts (project_id, tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_scripts_story_idea_tenant_user_updated
    ON creator_scripts (story_idea_id, tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_scripts_locked_idea_tenant_user_updated
    ON creator_scripts (locked_idea_id, tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_storyboards_tenant_user_updated
    ON creator_storyboards (tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_storyboards_project_idea_tenant_user_created
    ON creator_storyboards (project_id, idea_id, tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_storyboards_idea_tenant_user_created
    ON creator_storyboards (idea_id, tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_storyboard_scenes_storyboard_shot
    ON creator_storyboard_scenes (storyboard_id, shot_number);

CREATE INDEX IF NOT EXISTS idx_creator_assets_tenant_user_created
    ON creator_assets (tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_assets_project_type_created
    ON creator_assets (project_id, asset_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_assets_storyboard_type_created
    ON creator_assets (storyboard_id, asset_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_assets_script_images
    ON creator_assets (tenant_id, user_id, (metadata ->> 'scriptId'), asset_type, created_at DESC)
    WHERE asset_type IN ('STORYBOARD_IMAGE', 'LIGHTING_BUILD_SHEET_IMAGE', 'CAMERA_PLAN_SHEET_IMAGE');

CREATE INDEX IF NOT EXISTS idx_creator_assets_script_shot_kind
    ON creator_assets ((metadata ->> 'scriptId'), (metadata ->> 'shotNumber'), (metadata ->> 'imageKind'));
