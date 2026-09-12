-- Lets a wallet transaction be tied back to the project it was spent on, same raw-FK-passthrough
-- pattern as usage_records.project_id (V19) -- no project name/lookup here, just the id. Null for
-- charges not tied to a project (recharges, DID rental, subscription fees, manual adjustments).
ALTER TABLE transactions ADD COLUMN project_id UUID;

CREATE INDEX idx_transactions_project_id ON transactions (project_id) WHERE project_id IS NOT NULL;
