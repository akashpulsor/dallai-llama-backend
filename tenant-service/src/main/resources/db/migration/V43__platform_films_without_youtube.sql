-- A film made on Dalaillama is published to the creator's profile first; sharing it to YouTube is a
-- later, separate step. Such an item has no YouTube video yet, so youtube_video_id becomes optional
-- -- for platform films only -- and the item carries the film's own facts for its card. It plays from
-- our stamped copy (the SELF host) and its poster is the film's first frame.
ALTER TABLE showcase_item ALTER COLUMN youtube_video_id DROP NOT NULL;
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_video_or_platform_ck
    CHECK (youtube_video_id IS NOT NULL OR origin = 'PLATFORM');

-- RENDERED: published straight from the project; the proof is our own render.
ALTER TABLE showcase_item DROP CONSTRAINT showcase_item_proof_ck;
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_proof_ck
    CHECK (platform_proof IS NULL OR platform_proof IN ('UPLOADED', 'LINK_MATCH', 'RENDERED'));

ALTER TABLE showcase_item ADD COLUMN film_duration_seconds NUMERIC(8, 3);
ALTER TABLE showcase_item ADD COLUMN film_width INT;
ALTER TABLE showcase_item ADD COLUMN film_height INT;
