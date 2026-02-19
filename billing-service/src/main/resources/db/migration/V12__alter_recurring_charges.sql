ALTER TABLE recurring_charges ADD COLUMN subscription_id UUID;

CREATE INDEX idx_recurring_subscription ON recurring_charges(subscription_id);

ALTER TABLE transactions ADD COLUMN subscription_id UUID;
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(100) UNIQUE;
CREATE INDEX idx_txn_subscription ON transactions(subscription_id);
CREATE INDEX idx_txn_idempotency ON transactions(idempotency_key);