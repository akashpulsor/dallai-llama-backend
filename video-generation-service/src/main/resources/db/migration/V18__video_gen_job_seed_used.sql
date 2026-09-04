-- Persists the deterministic seed actually sent to the provider for this dispatch. Derived from
-- the project's locked_idea_id UUID at dispatch time (ShotGenerationOrchestrator.resolveSeed) --
-- one seed per project, shared across every shot, so character faces / set continuity / lighting
-- stay coherent across scenes. Persisted (not recomputed each time) so a job replay uses the
-- exact same seed the original dispatch used, and so a shot re-run through the same job path is
-- byte-reproducible from the provider's side. Nullable for backfill: pre-existing rows were
-- dispatched without a seed and can't have one attributed after the fact.
ALTER TABLE video_gen_job
    ADD COLUMN seed_used BIGINT;
