-- Adds amount/frequency/wallet_credit fields to rate_plans so the ops dashboard's Plans tab has
-- something to manage. Existing rate_plans rows (metadata-only) get zero amount and default
-- MONTHLY frequency; the ops UI blocks activation of any plan whose amount is still zero, so
-- nothing legacy silently becomes billable.
--
-- Frequency stays a text column mirroring recurring_charges.frequency ("MONTHLY", "QUARTERLY",
-- "YEARLY", "WEEKLY") so an existing recurring charge and its plan can be checked for consistency
-- with a single string compare rather than a lookup enum. UI only offers MONTHLY on the create
-- form (per current product ask); the schema keeps the door open for the other cycles when the
-- product decision changes.

ALTER TABLE rate_plans
    ADD COLUMN IF NOT EXISTS amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    ADD COLUMN IF NOT EXISTS frequency VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN IF NOT EXISTS wallet_credit_per_cycle NUMERIC(12, 2) NOT NULL DEFAULT 0;

-- A plan without an amount is a legacy metadata row -- keep it, but the ops UI's activation
-- guard refuses to enable it. Deliberately no NOT NULL/CHECK constraint on amount > 0; the
-- product should be free to seed a placeholder row and fill the amount later.
