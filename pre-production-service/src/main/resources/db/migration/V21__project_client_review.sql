ALTER TABLE project ADD COLUMN client_review_token VARCHAR(64) UNIQUE;
ALTER TABLE project ADD COLUMN chat_session_id UUID;
