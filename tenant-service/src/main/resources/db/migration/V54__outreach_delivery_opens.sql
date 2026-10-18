-- Open pixel in outreach mail: the first open's time is what the digest learns its send hour from
-- (with link clicks); the count is for analytics.
ALTER TABLE lead_outreach_delivery
    ADD COLUMN first_opened_at TIMESTAMPTZ,
    ADD COLUMN open_count      INT NOT NULL DEFAULT 0;
