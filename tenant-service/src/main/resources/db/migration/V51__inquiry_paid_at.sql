-- When the brand paid the brief made from this request (billing.payment.received). The creator is
-- mailed once, when this is first set.
ALTER TABLE lead_brand_inquiry ADD COLUMN paid_at TIMESTAMPTZ;
