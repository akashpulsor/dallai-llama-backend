-- The creator's own markup on top of the platform's standard rate for client-facing pricing.
-- Null/0 -- default -- means the client pays exactly the standard rate.
ALTER TABLE tenants ADD COLUMN margin_percent NUMERIC(5,2);
