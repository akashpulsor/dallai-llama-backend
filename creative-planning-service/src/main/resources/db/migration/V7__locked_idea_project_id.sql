-- Records the pre-production-service Project this locked idea produced, so a repeated lock call
-- (idempotency) can return the same project id without re-calling pre-production-service --
-- avoids either a duplicate project or a second network call on retry.
ALTER TABLE locked_idea ADD COLUMN project_id UUID;
