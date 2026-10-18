-- The daily send cap counts today's deliveries; the (recipient_hash, delivery_day) unique index
-- can't serve a lookup by day alone.
CREATE INDEX lead_outreach_delivery_day_idx ON lead_outreach_delivery (delivery_day);
