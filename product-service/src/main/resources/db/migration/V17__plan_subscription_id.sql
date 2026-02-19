ALTER TABLE plan_assignments ADD COLUMN subscription_id UUID;

CREATE INDEX idx_plan_assignment_subscription ON plan_assignments(subscription_id);