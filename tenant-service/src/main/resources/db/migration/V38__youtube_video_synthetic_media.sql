-- YouTube's "altered or synthetic content" disclosure (videos.list status.containsSyntheticMedia).
-- NULL = YouTube didn't say (older videos, or not fetched since this column existed).
ALTER TABLE youtube_video ADD COLUMN contains_synthetic_media BOOLEAN;
