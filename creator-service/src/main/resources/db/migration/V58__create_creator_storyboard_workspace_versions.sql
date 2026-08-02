CREATE TABLE creator_storyboard_workspace_versions
(
    id                      UUID PRIMARY KEY,

    workspace_id            UUID NOT NULL,

    version                 INTEGER NOT NULL,

    parent_version          INTEGER,

    message_id              VARCHAR(120),

    -- Full editable snapshot of the workspace's shots/scenes/plans, forked
    -- from the live script at workspace-open time. The live creator_scripts
    -- row is never touched until merge.
    workspace_payload       JSONB NOT NULL,

    -- Operations the AI applied to produce this version from its parent, for
    -- audit/diffing (not replayed - workspace_payload is always materialized).
    operations               JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- Shot numbers touched by this version, so retrieval/re-embedding can
    -- target only what changed instead of the whole workspace.
    dirty_shots              JSONB NOT NULL DEFAULT '[]'::jsonb,

    summary                  TEXT,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_storyboard_workspace_version_workspace
        FOREIGN KEY (workspace_id)
            REFERENCES creator_storyboard_workspaces(id)
            ON DELETE CASCADE,

    CONSTRAINT uq_storyboard_workspace_version
        UNIQUE (workspace_id, version)
);

CREATE INDEX idx_storyboard_workspace_versions_workspace
    ON creator_storyboard_workspace_versions (workspace_id);

CREATE INDEX idx_storyboard_workspace_versions_created
    ON creator_storyboard_workspace_versions (created_at DESC);
