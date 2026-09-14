-- Corrects seedance-v1's rates. V91 undercharged by 11.2x.
--
-- V91 derived its numbers from fal's "Seedance V1 Pro Fast" page, which prices video tokens at
-- $1.00 per MILLION. The variant this deployment actually describes is a Seedance 2.0 Fast
-- endpoint -- FalAiProvider documents 480p/720p with default 720p and no 1080p, and fal states
-- 1080p is "the only resolution option not available on fast" -- and that one bills at $0.0112
-- per THOUSAND tokens. An 11.2x difference, in the direction that loses money on every render.
--
-- Rates below are computed from fal's published formula for Seedance 2.0 Fast (checked
-- 2026-09-14): $0.0112 / 1,000 tokens, tokens = (height x width x duration x 24) / 1024.
--
--   720p  1280 x 720  -> 21,600.0 tok/s -> $0.24192/s
--   480p   854 x 480  ->  9,607.5 tok/s -> $0.10760/s
--
-- The 720p figure is the proof the formula is being read correctly: fal publishes $0.2419/sec for
-- 720p on that endpoint, and the formula reproduces it to five decimal places. The same formula
-- at the standard tier's $0.014/1,000 reproduces fal's published $0.3034/s (720p) and $0.682/s
-- (1080p) to within their own rounding, so it holds across tiers rather than fitting one point.
UPDATE rate_card SET per_second_cost = 0.1076000000
WHERE model_id = 'seedance-v1' AND resolution = '480p';

UPDATE rate_card SET per_second_cost = 0.2419200000
WHERE model_id = 'seedance-v1' AND resolution = '720p';

-- Fast has no 1080p. Leaving a row for it invites billing a tier this endpoint cannot render,
-- and quoting a creator a price for output they will never receive.
DELETE FROM rate_card WHERE model_id = 'seedance-v1' AND resolution = '1080p';

-- Tier-less fallback carries 720p's rate: that is the endpoint's own default, and it is the
-- dearer of the two, so a request that names no resolution cannot come in under cost.
UPDATE rate_card SET per_second_cost = 0.2419200000
WHERE model_id = 'seedance-v1' AND resolution IS NULL;
