-- Normalizes screenplay video scene/combined-video state onto creator_assets instead of
-- leaving it embedded in creator_generation_jobs.output_payload JSONB, where an unrelated
-- job failure (e.g. a billing debit error after a successful merge) can orphan an already
-- generated video from every "latest run" lookup. All columns are nullable/defaulted so
-- every existing creator_assets row (avatars, reference images, generated audio, etc.)
-- stays valid with no backfill required for this migration itself.

ALTER TABLE creator_assets
    ADD COLUMN script_id UUID NULL,
    ADD COLUMN run_id UUID NULL,
    ADD COLUMN shot_number INTEGER NULL,
    ADD COLUMN provider VARCHAR(64) NULL,
    ADD COLUMN duration_seconds INTEGER NULL,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'READY',
    ADD COLUMN accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN accepted_at TIMESTAMPTZ NULL,
    ADD COLUMN accepted_by VARCHAR(128) NULL,
    ADD COLUMN is_combined BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE creator_assets
    ADD CONSTRAINT fk_creator_assets_script
        FOREIGN KEY (script_id) REFERENCES creator_scripts (id) ON DELETE CASCADE;

CREATE INDEX idx_creator_assets_script_type ON creator_assets (script_id, asset_type);
CREATE INDEX idx_creator_assets_run_combined ON creator_assets (run_id, is_combined);
CREATE INDEX idx_creator_assets_script_accepted ON creator_assets (script_id, accepted);

-- At most one combined-video asset per run, at most one scene asset per (run, shot).
CREATE UNIQUE INDEX ux_creator_assets_run_combined
    ON creator_assets (run_id)
    WHERE is_combined = true;

CREATE UNIQUE INDEX ux_creator_assets_run_shot
    ON creator_assets (run_id, shot_number)
    WHERE is_combined = false AND shot_number IS NOT NULL;
