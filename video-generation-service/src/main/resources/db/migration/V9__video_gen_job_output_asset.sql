-- Durable copy of the generated clip in our own MinIO -- provider-hosted URLs (fal.ai's own CDN)
-- are not guaranteed durable/authless, so output_uri alone isn't enough for the UI to display or
-- for the export bundle / post-production-service to depend on long-term.
ALTER TABLE video_gen_job ADD COLUMN output_bucket VARCHAR(128);
ALTER TABLE video_gen_job ADD COLUMN output_object_key VARCHAR(1024);
