ALTER TABLE creator_shot_enhancement_variants
    ADD COLUMN IF NOT EXISTS final_video_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS final_audio_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS provider VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_operation_id TEXT,
    ADD COLUMN IF NOT EXISTS provider_request JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS provider_response JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_creator_shot_enhancement_variants_final_video
    ON creator_shot_enhancement_variants(final_video_asset_id)
    WHERE final_video_asset_id IS NOT NULL;

COMMENT ON COLUMN creator_shot_enhancement_variants.final_video_asset_id IS
    'Final polished video generated from the accepted take/reference frame, currently via Google Veo.';
COMMENT ON COLUMN creator_shot_enhancement_variants.final_audio_asset_id IS
    'Optional separated/mixed audio render for the polished take when a provider returns standalone audio.';
COMMENT ON COLUMN creator_shot_enhancement_variants.provider IS
    'Generation provider used for this variant, for example google_veo or gemini_image.';
COMMENT ON COLUMN creator_shot_enhancement_variants.provider_operation_id IS
    'External provider long-running operation id/name for diagnostics and polling.';
