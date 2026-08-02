CREATE TABLE creator_storyboard_workspaces
(
    id                      UUID PRIMARY KEY,

    script_id               UUID NOT NULL,

    tenant_id               VARCHAR(128) NOT NULL,

    user_id                 VARCHAR(128) NOT NULL,

    -- ACTIVE, MERGED, ABANDONED
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    current_version         INTEGER NOT NULL DEFAULT 1,

    active_checkpoint_id    UUID,

    title                   VARCHAR(255),

    conversation_summary    TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_storyboard_workspace_script
        FOREIGN KEY (script_id)
            REFERENCES creator_scripts(id)
            ON DELETE CASCADE
);

CREATE INDEX idx_storyboard_workspace_script
    ON creator_storyboard_workspaces (script_id);

CREATE INDEX idx_storyboard_workspace_tenant_user
    ON creator_storyboard_workspaces (tenant_id, user_id, updated_at DESC);

CREATE INDEX idx_storyboard_workspace_status
    ON creator_storyboard_workspaces (status);
