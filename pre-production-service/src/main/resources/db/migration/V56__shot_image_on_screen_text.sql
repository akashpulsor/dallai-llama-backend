-- Per-image record of "does this rendered image contain visible on-screen text, and in what
-- language" -- populated by ShotImageDescriptionService's vision call. Previously the analysis
-- prompt (llm-gateway V50) already asked the model for onScreenText but the deserialized field
-- was immediately discarded on read (ShotImageDescriptionService.describe returned only
-- `description`). Persisting it here + backing UI affordances against it (instead of the coarse
-- shot-type allowlist TEXT_BEARING_KINDS in ShotImagesPanel.jsx) makes "has text" a real
-- per-image signal: a PRODUCTION frame with signage/packaging shows text affordances, one
-- without doesn't; a MOTION_GRAPHIC that failed to render its text correctly stops falsely
-- promising text-only edits.
--
-- "Has text" is derived: (on_screen_text IS NOT NULL AND on_screen_text != ''), no boolean
-- column. Language is a new field the V50 prompt didn't ask for; llm-gateway V83 (paired
-- migration) extends the prompt template to also request it.
ALTER TABLE shot_image ADD COLUMN on_screen_text TEXT;
ALTER TABLE shot_image ADD COLUMN on_screen_text_language VARCHAR(16);
