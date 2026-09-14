-- Price duration-priced models by resolution, not just by model.
--
-- rate_card was keyed on model_id alone, so one per_second_cost covered every tier a model can
-- render. fal.ai does not price that way: Wan 3.0 Prime is $0.068/s at 480p and $0.28/s at 1080p,
-- a 4x spread billed at a single number. Whichever tier the stored rate was written for was
-- correct by luck and every other tier was wrong. The estimate shown to a creator and the debit
-- taken from their wallet both come from this table, so both carried the error.
ALTER TABLE rate_card ADD COLUMN resolution VARCHAR(16);

-- NULL means "applies at any resolution" -- the correct answer for token-priced models, which
-- have no such concept, and the fallback when a request names no tier.
COMMENT ON COLUMN rate_card.resolution IS
    'Render tier this rate applies to (e.g. 480p, 720p). NULL applies at any resolution.';

CREATE INDEX idx_rate_card_model_resolution_effective
    ON rate_card (model_id, resolution, effective_from DESC);

-- Wan 3.0 Prime, read from fal.ai's own model page for alibaba/wan-3.0-prime/image-to-video
-- (checked 2026-09-14): "For every second of video you generate, you will be charged $0.068 at
-- 480p, $0.14 at 720p, or $0.28 at 1080p."
--
-- The rates this replaces -- $0.05 at 480p, $0.20 at 1080p, quoted in FalAiProvider's javadoc --
-- are the STANDARD Wan 3.0 schedule. This deployment runs Prime, which fal sells at roughly 1.4x
-- standard for accelerated generation. So every Wan render since launch has been billed about 26%
-- under what fal actually charges, on top of the missing-tier problem: a loss on every clip, not a
-- rounding error.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, per_second_cost, resolution)
VALUES
    ('alibaba/wan-3.0-prime', 0, 0, 'USD', 0.0680000000, '480p'),
    ('alibaba/wan-3.0-prime', 0, 0, 'USD', 0.1400000000, '720p'),
    ('alibaba/wan-3.0-prime', 0, 0, 'USD', 0.2800000000, '1080p');

-- The pre-existing tier-less row still answers a request that names no resolution. Wan's own
-- cost-policy default is 480p (FalAiProvider.DEFAULT_WAN_RESOLUTION), so it should hold 480p's
-- real rate rather than the standard-schedule figure it was seeded with.
UPDATE rate_card
SET per_second_cost = 0.0680000000
WHERE model_id = 'alibaba/wan-3.0-prime' AND resolution IS NULL;

-- seedance-v1 is deliberately left alone. Its $0.055/s came from V58 citing fal's 720p rate, but
-- that model_id does not correspond to a current fal.ai model path the way 'alibaba/wan-3.0-prime'
-- does, and fal now lists several Seedance variants at very different prices (2.0 Fast at
-- $0.2419/s for 720p, 2.0 Standard at $0.3034/s, V1 Pro Fast priced per token). Which one this row
-- means cannot be established from this repo, and a billing rate is not a thing to guess at.
-- Identify the exact variant, then price its tiers the way Wan's are priced above.
