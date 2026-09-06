-- Persists the output resolution requested for this dispatch (see VideoResolution) so approve()
-- can forward it to the provider. Nullable: null means "use the provider's own default"
-- (Seedance: 720p, Wan: 480p) -- same semantics as duration_seconds/aspect_ratio above it.
ALTER TABLE video_gen_job
    ADD COLUMN resolution VARCHAR(16);
