-- Creator Showcase (docs/lead-management/CREATOR_SHOWCASE.md rules 9-10): the client agrees, when
-- paying to lock, that the finished film may be used to promote the creator and Dalaillama.
-- Stored as the terms version they accepted and when. Films without it never appear in brand mail
-- or on Dalaillama's own channel.
ALTER TABLE project ADD COLUMN marketing_terms_version VARCHAR(16);
ALTER TABLE project ADD COLUMN marketing_terms_accepted_at TIMESTAMPTZ;
