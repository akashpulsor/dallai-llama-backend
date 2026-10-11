-- A lead's role at the company (CSV 'designation'/'title'/'position', or the provider's position).
ALTER TABLE lead_creator_lead ADD COLUMN designation VARCHAR(120);
