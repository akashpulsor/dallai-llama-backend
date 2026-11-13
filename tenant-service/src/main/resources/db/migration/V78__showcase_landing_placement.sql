-- Where ops place a creator's film on the landing page, apart from their profile: taken off the
-- landing page (it stays on the profile and in the feeds), or put in the hero at the top, newest
-- first. A hero is never also taken off.
ALTER TABLE showcase_item ADD COLUMN landing_hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE showcase_item ADD COLUMN landing_hero_at TIMESTAMPTZ;
ALTER TABLE showcase_item ADD CONSTRAINT showcase_item_landing_hero_not_hidden
    CHECK (NOT (landing_hidden AND landing_hero_at IS NOT NULL));
