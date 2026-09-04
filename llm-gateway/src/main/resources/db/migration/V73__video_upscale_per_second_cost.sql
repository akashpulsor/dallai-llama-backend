-- Closes the real-cost gap for the two duration-priced models seeded in V71/V72: their rate_card
-- rows were inserted with per_second_cost left NULL, so LlmGatewayService.computeCost's existing
-- "video"/"upscale" per-second branch (see LlmGatewayService.java) never fired and both always
-- billed $0 regardless of real fal.ai cost.
--
-- alibaba/wan-3.0-prime: $0.05/s, verified against fal.ai's real published rate for 480p output --
-- FalAiProvider.wanVideoRequestBody now defaults resolution to 480p (never fal.ai's own 1080p
-- default) as a deliberate cost policy: generate cheap, upscale for delivery quality, rather than
-- pay 1080p generation cost ($0.20/s).
--
-- fal-ai/topaz/upscale/video: $0.02/s, verified against fal.ai's real published tiered rate ($0.01
-- up to 720p, $0.02 720p-1080p, $0.08 above 1080p) -- $0.02 matches LlmGatewayUpscaleGenerationService's
-- actual default call shape (no upscale_factor override, Topaz's own default of 2x on a 480p
-- source lands in the 720p-1080p tier). An upscale call to a higher tier than that costs more than
-- this row assumes -- same documented-approximation precedent as every other duration-priced row
-- in this catalog (see V14), not exact per-call metering.
UPDATE rate_card SET per_second_cost = 0.05 WHERE model_id = 'alibaba/wan-3.0-prime';
UPDATE rate_card SET per_second_cost = 0.02 WHERE model_id = 'fal-ai/topaz/upscale/video';
