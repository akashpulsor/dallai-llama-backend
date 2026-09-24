-- Adds narrative_language to project_config so the creator can pick "prose in English + dialogue
-- in Hindi" (the common Indian-market shape) without one field having to mean both. Existing
-- dialogue_language keeps its meaning (only spoken lines); narrative_language covers scriptText /
-- logline / screenplay scene summaries.
--
-- Nullable, no default: null at read time falls back to en-US via
-- ProjectConfigService.resolveNarrativeLanguage, keeping historical rows working without a
-- destructive backfill that would guess wrong for creators who never picked one.
ALTER TABLE project_config
    ADD COLUMN IF NOT EXISTS narrative_language VARCHAR(16);
