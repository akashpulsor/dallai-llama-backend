-- Project-scene preparation lifecycle indicator. Toggled by the bulk shot-prepare loop
-- (PrepareSceneController.prepareShotsBatch) so the UI can poll the preparation row and know
-- whether a batch is still running (PREPARING), finished (READY), or gave up (FAILED). Per-shot
-- prepare doesn't touch this -- it's a project-wide flag, not per-shot. Default READY so
-- existing rows (produced by Stage-1 template-only prepare before this column existed) stay
-- consistent with their observable state.
ALTER TABLE project_scene_preparation
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'READY';
