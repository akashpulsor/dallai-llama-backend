-- Inspiration plays in our own player (no third-party embed or branding): the pin's video file
-- and length, filled from the pin's public info with its picture and maker (V56).
ALTER TABLE inspiration_item ADD COLUMN video_url VARCHAR(500);
ALTER TABLE inspiration_item ADD COLUMN duration_seconds NUMERIC(8, 2);
