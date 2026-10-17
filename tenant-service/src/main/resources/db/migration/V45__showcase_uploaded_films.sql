-- A creator can upload a video file to their profile -- typically one that is private on their
-- YouTube channel, downloaded from YouTube Studio. We keep our own copy and play it in our own
-- player; its thumbnail is a frame the creator picked (or an image they uploaded). Both live in
-- MinIO; these are their object keys. Null for picks from the channel and for platform films.
ALTER TABLE showcase_item ADD COLUMN upload_object_key VARCHAR(512);
ALTER TABLE showcase_item ADD COLUMN poster_object_key VARCHAR(512);
-- An item shows a YouTube video, a platform film, or now an uploaded video.
ALTER TABLE showcase_item DROP CONSTRAINT showcase_item_video_or_platform_ck;
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_video_or_platform_ck
    CHECK (youtube_video_id IS NOT NULL OR origin = 'PLATFORM' OR upload_object_key IS NOT NULL);
