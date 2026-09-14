-- Price duration-priced models by resolution, not just by model.
--
-- rate_card was keyed on model_id alone, so one per_second_cost covered every tier a model can
-- render. fal.ai does not price that way: Wan is $0.05/s at 480p and $0.20/s at 1080p -- a 4x
-- spread billed at a single number. Whichever tier the stored rate was written for was correct by
-- luck and every other tier was wrong, over- or under-charging depending on which way it missed.
-- The estimate shown to a creator and the debit taken from their wallet both come from this
-- table, so both carried the error.
ALTER TABLE rate_card ADD COLUMN resolution VARCHAR(16);

-- NULL means "applies at any resolution" -- the correct answer for token-priced models, which
-- have no such concept, and the fallback for a duration-priced model whose tier we have not
-- priced yet. Existing rows keep NULL, so nothing changes until a tier row exists.
COMMENT ON COLUMN rate_card.resolution IS
    'Render tier this rate applies to (e.g. 480p, 720p). NULL applies at any resolution.';

CREATE INDEX idx_rate_card_model_resolution_effective
    ON rate_card (model_id, resolution, effective_from DESC);

-- Rates below are the ones this codebase already documents against fal.ai's published schedule;
-- no tier is priced here on a guess. See FalAiProvider.DEFAULT_WAN_RESOLUTION's javadoc for Wan
-- (480p $0.05/s, fal.ai's own default 1080p $0.20/s) and V58's comment for Seedance (720p
-- $0.055/s, the Fast variant has no 1080p).
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, per_second_cost, resolution)
VALUES
    ('alibaba/wan-3.0-prime', 0, 0, 'USD', 0.0500000000, '480p'),
    ('alibaba/wan-3.0-prime', 0, 0, 'USD', 0.2000000000, '1080p'),
    ('seedance-v1',           0, 0, 'USD', 0.0550000000, '720p');

-- Deliberately NOT seeded, because no source in this repo states them: Wan at 720p, and Seedance
-- at 480p. Until they are, a request at those tiers resolves through the safety path in
-- ModelRouterService.rateCardFor -- the most expensive known rate for the model, logged as a
-- warning -- rather than silently billing a cheaper tier's rate and losing money on every render.
