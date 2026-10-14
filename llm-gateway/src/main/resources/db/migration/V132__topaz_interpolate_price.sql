-- topaz/interpolate/video went in (V131) with no price, so every slow-down with AI frames was
-- logged at cost 0 and billing-service skipped it -- nobody was charged.
--
-- Topaz bills per GENERATED frame: fal's own example is $0.30 for a 10s 30->60fps conversion at
-- 1080p, i.e. 300 new frames, about $0.001 a frame. A slow-down's params carry the OUTPUT length as
-- duration_seconds (source x factor). At 30fps a 2x slow-down generates about 15 frames per output
-- second, so $0.015 an output second. Our clips are mostly 480p, which Topaz prices lower, so this
-- errs on the side of covering the provider; refine against fal's invoice.
UPDATE rate_card SET per_second_cost = 0.015 WHERE model_id = 'topaz/interpolate/video';
