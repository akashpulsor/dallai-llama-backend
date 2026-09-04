-- Script was one-row-per-project (project_id UNIQUE) with every regenerate/edit overwriting it
-- destructively -- no history, no rollback, same problem V13 already fixed for screenplay. Unlike
-- that fix, this does NOT touch the `script` table itself: script_character and every other
-- service in this codebase (cast assignment, continuity bible, dialogue details, shot context
-- assembly, shot generation) resolve a project's script via `script.project_id` and then join on
-- `script.id` -- turning `script` itself into a multi-row-per-project table would mean auditing
-- and rewriting every one of those call sites to know which version's id to join against, for a
-- benefit (rollback) none of them need. `script` stays exactly as it is: one row, always the
-- current draft, unique constraint intact, every existing read path untouched.
--
-- script_version is purely an additive history log alongside it: one row per generate()/saveEdit()
-- call, holding a full snapshot of the versioned narrative fields (never script_character rows --
-- those aren't versioned, same as how screenplay's own characters live on `script` and aren't
-- duplicated per screenplay version either). ScriptGenerationService keeps `script` in sync with
-- whatever the latest version's content is; this table is what version-history/rollback UI reads.
CREATE TABLE script_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id),
    script_id UUID NOT NULL REFERENCES script(id),
    version INTEGER NOT NULL,
    source VARCHAR(16) NOT NULL,
    parent_id UUID REFERENCES script_version(id),
    script_text TEXT NOT NULL,
    pacing_style VARCHAR(240),
    emotional_arc TEXT,
    hook_strategy TEXT,
    no_humans BOOLEAN NOT NULL DEFAULT FALSE,
    logline TEXT,
    central_conflict TEXT,
    ending_payoff TEXT,
    setting TEXT,
    hook TEXT,
    storytelling_type VARCHAR(80),
    -- The critic's actual feedback that forced this rewrite -- only ever set for source=CRITIC,
    -- so a version-history UI can show not just "the critic revised this" but why.
    critique_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_script_version_project_version ON script_version(project_id, version DESC);

-- Backfill: every script generated before this migration gets an initial version 1 snapshot of
-- its current content, so it shows up in version history immediately rather than looking empty.
INSERT INTO script_version (
    id, tenant_id, project_id, script_id, version, source, parent_id,
    script_text, pacing_style, emotional_arc, hook_strategy, no_humans,
    logline, central_conflict, ending_payoff, setting, hook, storytelling_type, critique_notes, created_at
)
SELECT gen_random_uuid(), tenant_id, project_id, id, 1, 'GENERATED', NULL,
    script_text, pacing_style, emotional_arc, hook_strategy, no_humans,
    logline, central_conflict, ending_payoff, setting, hook, storytelling_type, NULL, created_at
FROM script;
