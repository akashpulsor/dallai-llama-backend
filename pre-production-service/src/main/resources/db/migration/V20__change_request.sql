CREATE TABLE change_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id),
    target_type VARCHAR(32) NOT NULL REFERENCES suggestion_target_type(code),
    target_ref VARCHAR(200),
    note TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_change_request_project_id ON change_request(project_id);
