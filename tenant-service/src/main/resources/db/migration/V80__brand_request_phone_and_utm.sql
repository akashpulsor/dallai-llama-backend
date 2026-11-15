-- Ads bring brands to the site's request form: their phone (WhatsApp), so ops can reply fast, and
-- which ad brought the request (utm_source / utm_campaign), so a paid brief can be traced to its ad.
ALTER TABLE lead_brand_contact ADD COLUMN phone VARCHAR(20);
ALTER TABLE lead_brand_inquiry ADD COLUMN utm_source VARCHAR(64);
ALTER TABLE lead_brand_inquiry ADD COLUMN utm_campaign VARCHAR(64);
