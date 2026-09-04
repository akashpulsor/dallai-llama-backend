-- A client's review feedback: a comment plus an optional reference image, tied to the review
-- session it was left in. Deliberately not a ChangeRequest (see suggestion_target_type) --
-- this isn't a regeneration instruction the model classified, it's raw feedback for the creator
-- to read and act on however they see fit.
CREATE TABLE review_comment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id),
    review_id UUID REFERENCES client_review_session(id),
    content TEXT NOT NULL,
    image_bucket VARCHAR(200),
    image_object_key VARCHAR(500),
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_review_comment_project_id ON review_comment(project_id);
