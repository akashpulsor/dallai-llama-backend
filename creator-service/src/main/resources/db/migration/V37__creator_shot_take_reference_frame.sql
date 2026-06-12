ALTER TABLE creator_shot_takes
    ADD COLUMN IF NOT EXISTS reference_frame_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_creator_shot_takes_reference_frame
    ON creator_shot_takes(reference_frame_asset_id)
    WHERE reference_frame_asset_id IS NOT NULL;

COMMENT ON COLUMN creator_shot_takes.reference_frame_asset_id IS
    'Still frame uploaded/extracted from the recorded take and used as the image-to-image anchor for Gemini polish previews.';
