CREATE TABLE shot_plan_issue (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    shot_ref VARCHAR(64) NOT NULL,
    category VARCHAR(40) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_shot_plan_issue_project_id ON shot_plan_issue(project_id);
