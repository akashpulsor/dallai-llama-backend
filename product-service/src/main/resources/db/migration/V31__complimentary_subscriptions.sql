-- Ops can give a creator a paid plan free for a set time (a complimentary grant): no charge and no
-- renewal; it simply ends at expires_at, and the creator is back on the free plan.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS complimentary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS grant_note VARCHAR(300);
