-- What percentage of quoted_total_price the client must pay to unlock the project (see
-- ProjectRequirement#getRequiredAmount) -- lets a creator ask for a partial "token" payment
-- instead of the full quote, with the remainder collected outside the app. Default 100 keeps
-- every existing requirement's behavior unchanged (full payment required, as today).
ALTER TABLE project_requirement ADD COLUMN required_payment_percent INTEGER NOT NULL DEFAULT 100;
