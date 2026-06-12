ALTER TABLE creator_shot_enhancement_variants
    ADD COLUMN IF NOT EXISTS final_render_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_creator_shot_enhancement_variants_final_render
    ON creator_shot_enhancement_variants(final_render_asset_id)
    WHERE final_render_asset_id IS NOT NULL;

COMMENT ON COLUMN creator_shot_enhancement_variants.final_render_asset_id IS
    'Final baked MP4 for the polished shot after video, mixed/enhanced audio, and text overlays are rendered together.';
