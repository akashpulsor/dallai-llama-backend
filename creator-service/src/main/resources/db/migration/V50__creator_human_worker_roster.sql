CREATE TABLE IF NOT EXISTS creator_human_workers (
    user_id VARCHAR(160) PRIMARY KEY,
    role VARCHAR(40) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    email VARCHAR(240),
    online BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    active_assignment_count INTEGER NOT NULL DEFAULT 0,
    total_assignment_count BIGINT NOT NULL DEFAULT 0,
    last_assigned_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_creator_human_workers_role_online
    ON creator_human_workers (role, online, active, total_assignment_count, last_assigned_at NULLS FIRST);

CREATE INDEX IF NOT EXISTS idx_creator_human_work_orders_assignee_queue
    ON creator_human_work_orders (assigned_to, work_type, status, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_human_work_orders_unassigned_queue
    ON creator_human_work_orders (work_type, status, submitted_at ASC)
    WHERE assigned_to IS NULL;

COMMENT ON TABLE creator_human_workers IS
    'Copywriter, editor, and operations users registered by Keycloak login and tracked for human creative work dispatch.';
