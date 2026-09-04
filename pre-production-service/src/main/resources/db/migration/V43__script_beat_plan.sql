-- ScriptGenerationService.generateHookBeatPlan already runs on every script generation and
-- materially shapes the result (the script is written from this structure), but the plan itself
-- was thrown away the moment the script call returned -- no way for a creator to see what beat
-- structure their script actually came from. Persisted from here on; nullable since the hook/beat
-- call is itself best-effort (falls back to an empty plan on any failure -- see the try/catch
-- around it) and existing scripts predate this column entirely.
ALTER TABLE script ADD COLUMN beat_plan TEXT;
