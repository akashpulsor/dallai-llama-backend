-- Project-level default video resolution. Applies to every shot's video generation unless the
-- caller passes an explicit per-shot resolutionOverride at prepare time (see PrepareShotRequest
-- in video-generation-service, which stays authoritative when set). NULL means "use each
-- provider's own default" (Seedance: 720p, Wan: 480p) -- same semantics as
-- VideoResolution.fromWireValue's null branch, so this is a compatible additive change.
--
-- Only 480p and 720p are supported values matching the VideoResolution enum in
-- video-generation-service; 1080p was deliberately omitted from that enum because no provider
-- currently supports it (see VideoResolution.java's class-level comment). If a caller writes
-- something else, VideoResolution.fromWireValue tolerantly maps it to null so the request
-- doesn't fail on entry.
ALTER TABLE project_config
    ADD COLUMN preferred_resolution VARCHAR(16);
