-- The per-second video rate, set from the ops page instead of a redeploy.
--
-- One row, id = 1. VideoPricingService reads it on every quote; with no row it falls back to
-- billing.video-pricing.base-rate-per-second-inr, so an environment that never touches the ops
-- page prices exactly as before. A quote is snapshotted onto its brief when the brief is created,
-- so changing the rate re-prices new briefs only -- never one a client has already been shown.
CREATE TABLE video_pricing_config (
    id                        SMALLINT       PRIMARY KEY CHECK (id = 1),
    base_rate_per_second_inr  NUMERIC(15, 4) NOT NULL CHECK (base_rate_per_second_inr > 0),
    updated_at                TIMESTAMPTZ    NOT NULL
);
