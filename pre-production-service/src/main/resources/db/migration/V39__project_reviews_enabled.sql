-- Lets the creator shut client reviews off (and back on) for a project on demand. Default TRUE
-- so existing projects keep accepting reviews. When FALSE, opening a new review is refused.
ALTER TABLE project ADD COLUMN reviews_enabled BOOLEAN NOT NULL DEFAULT TRUE;
