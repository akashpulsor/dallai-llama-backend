-- Checkpoints are named bookmarks onto a specific workspace_version, not a
-- separate payload copy. History lives in creator_storyboard_workspace_versions;
-- a checkpoint just labels a version worth returning to (e.g. "Client Approved").
CREATE TABLE creator_storyboard_checkpoints
(
    id                      UUID PRIMARY KEY,

    workspace_id            UUID NOT NULL,

    version                 INTEGER NOT NULL,

    title                   VARCHAR(255) NOT NULL,

    created_by              VARCHAR(128),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_storyboard_checkpoint_workspace_version
        FOREIGN KEY (workspace_id, version)
            REFERENCES creator_storyboard_workspace_versions(workspace_id, version)
            ON DELETE CASCADE
);

CREATE INDEX idx_storyboard_checkpoint_workspace
    ON creator_storyboard_checkpoints (workspace_id);

CREATE INDEX idx_storyboard_checkpoint_created
    ON creator_storyboard_checkpoints (created_at DESC);

ALTER TABLE creator_storyboard_workspaces
    ADD CONSTRAINT fk_storyboard_workspace_checkpoint
        FOREIGN KEY (active_checkpoint_id)
            REFERENCES creator_storyboard_checkpoints(id)
            ON DELETE SET NULL;
