ALTER TABLE IF EXISTS subscriptions
ADD COLUMN IF NOT EXISTS requested_did_number VARCHAR(30),
ADD COLUMN IF NOT EXISTS requested_did_country VARCHAR(10),
ADD COLUMN IF NOT EXISTS requested_did_region VARCHAR(50),
ADD COLUMN IF NOT EXISTS requested_did_city VARCHAR(50);
