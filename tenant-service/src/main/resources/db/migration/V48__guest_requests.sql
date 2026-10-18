-- "Request a video" without signing in: the request's email becomes (or finds) the brand, unverified,
-- with source REQUEST so ops can tell walk-ins from sign-ups and imports.
ALTER TABLE lead_brand_contact DROP CONSTRAINT lead_brand_contact_source_ck;
ALTER TABLE lead_brand_contact ADD CONSTRAINT lead_brand_contact_source_ck CHECK (source IN ('SIGNUP', 'OPS_IMPORT', 'REQUEST'));
