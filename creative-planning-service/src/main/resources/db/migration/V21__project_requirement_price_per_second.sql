-- A brief priced by length (a brand's request at the platform's rate) keeps its rate, so changing
-- the length on the brief re-prices it. Null: priced by hand, or at the creator's own rate.
ALTER TABLE project_requirement ADD COLUMN price_per_second NUMERIC(12, 4);
