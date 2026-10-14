-- The length the shot list planned, kept when the creator changes the shot's length (to fit its
-- line, or by hand), so the studio can show "planned 5s, now 7s". Existing shots take their
-- current length: what they were first planned at was not recorded.
ALTER TABLE shot ADD COLUMN original_duration_seconds INTEGER;
UPDATE shot SET original_duration_seconds = duration_seconds;
