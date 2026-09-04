-- Adds critique-loop provenance to lighting_plan/camera_plan, same convention Script already has
-- (source GENERATED/EDITED/CRITIC, critique_notes carrying the actual feedback when CRITIC).
-- No separate version table like ScriptVersion -- these stay "one plan per shot, regenerate
-- overwrites", same as MotionGraphicPlan; the tag just says how the current row came to be.
-- Backfill: every existing plan predates this column, so GENERATED is the honest default (none of
-- them went through a critique loop that didn't exist yet).
ALTER TABLE lighting_plan ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'GENERATED';
ALTER TABLE lighting_plan ALTER COLUMN source DROP DEFAULT;
ALTER TABLE lighting_plan ADD COLUMN critique_notes TEXT;

ALTER TABLE camera_plan ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'GENERATED';
ALTER TABLE camera_plan ALTER COLUMN source DROP DEFAULT;
ALTER TABLE camera_plan ADD COLUMN critique_notes TEXT;
