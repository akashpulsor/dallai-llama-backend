ALTER TABLE payments ADD COLUMN project_requirement_id UUID;

-- Partial index: only project-scoped payments are ever queried by this column,
-- and most rows (wallet top-ups, subscription payments) leave it null.
CREATE INDEX idx_payment_project_requirement ON payments(project_requirement_id)
    WHERE project_requirement_id IS NOT NULL;
