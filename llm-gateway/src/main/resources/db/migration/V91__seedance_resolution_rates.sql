-- Price seedance-v1 per tier, like Wan.
--
-- fal.ai prices this one by video tokens rather than by the second: "Each 1080p 5 second video
-- costs roughly $0.243. For other resolutions, 1 million video tokens costs $1.0.
-- tokens(video) = (height x width x FPS x duration) / 1024" (checked 2026-09-14).
--
-- Converted to the per-second shape rate_card stores, at 24fps:
--   tokens/sec = (h * w * 24) / 1024,  cost/sec = tokens/sec / 1_000_000
--   480p   854 x 480  ->  9,601 tok/s  -> $0.0096/s
--   720p  1280 x 720  -> 21,600 tok/s  -> $0.0216/s
--   1080p 1920 x 1080 -> 48,600 tok/s  -> $0.0486/s
--
-- The 1080p figure is the check: 0.0486 x 5 = $0.243, exactly fal's own worked example, so the
-- formula is being read correctly rather than guessed at.
--
-- This replaces $0.055/s from V58, which cited a per-second rate this model does not have -- about
-- 2.5x over at 720p, so Seedance renders have been billed well above cost, the opposite of Wan's
-- error and just as wrong.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, per_second_cost, resolution)
VALUES
    ('seedance-v1', 0, 0, 'USD', 0.0096000000, '480p'),
    ('seedance-v1', 0, 0, 'USD', 0.0216000000, '720p'),
    ('seedance-v1', 0, 0, 'USD', 0.0486000000, '1080p');

-- Tier-less fallback: Seedance's own default render tier here is 720p (FalAiProvider sends it
-- explicitly), so the row a resolution-less request lands on should carry 720p's rate.
UPDATE rate_card
SET per_second_cost = 0.0216000000
WHERE model_id = 'seedance-v1' AND resolution IS NULL;
