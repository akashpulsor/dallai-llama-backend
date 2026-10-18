-- What share of a brief made from a brand's request the brand pays upfront, per creator. A
-- request sent to a creator is priced at the creator's own rate with this share due upfront; a
-- request sent to Dalaillama uses the platform's price and share instead (brands.request-pricing).
ALTER TABLE creator_public_profile ADD COLUMN request_prepayment_percent INT NOT NULL DEFAULT 40;
ALTER TABLE creator_public_profile ADD CONSTRAINT creator_public_profile_prepayment_ck
    CHECK (request_prepayment_percent BETWEEN 1 AND 100);
