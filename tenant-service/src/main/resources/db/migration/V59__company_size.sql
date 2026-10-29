-- How big a brand is (SMALL, MEDIUM, LARGE, ENTERPRISE), from the uploaded CSV's size column; null
-- when nobody said.
ALTER TABLE lead_company ADD COLUMN size_band VARCHAR(12);
ALTER TABLE lead_creator_lead ADD COLUMN size_band VARCHAR(12);
