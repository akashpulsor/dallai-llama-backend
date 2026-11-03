-- A request sent to Dalaillama is held by Dalaillama's own creator account (the founder's) so the
-- brand gets its brief and pays at once; once paid it is offered to creators until matching_until,
-- like a ride waiting for a driver. A creator who accepts takes it (brief and payment move to
-- them); when nobody does in time, it stays with the founder, who makes it.
ALTER TABLE lead_brand_inquiry
    ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN matching_until TIMESTAMPTZ;

CREATE INDEX ix_lead_brand_inquiry_matching ON lead_brand_inquiry (matching_until) WHERE provisional AND matching_until IS NOT NULL;
