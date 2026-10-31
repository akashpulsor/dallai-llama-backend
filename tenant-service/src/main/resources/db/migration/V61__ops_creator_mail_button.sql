-- A creator mail can end with a button (label + link), drawn in the platform's mail style.
ALTER TABLE ops_creator_mail_template ADD COLUMN cta_label VARCHAR(40);
ALTER TABLE ops_creator_mail_template ADD COLUMN cta_url VARCHAR(300);

-- The starter templates (V60) speak to any creator, with or without a profile, and send them to the
-- site with a button. Only rewritten where ops haven't edited them yet.
UPDATE ops_creator_mail_template SET
    name = 'Get found by brands (it''s free)',
    subject = '{name}, brands are searching for creators like you',
    body = 'Hi {name},

Brands come to Dalai Llama, and find us on Google, when they need a creator to make their videos.

Creators with a free profile and a video or two are the ones they find. It takes two minutes, costs nothing, and brands can then send you paid requests directly.',
    cta_label = 'Set up my free profile',
    cta_url = 'https://creator.dalaillama.in/profile',
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c1' AND updated_at = created_at;

UPDATE ops_creator_mail_template SET
    body = 'Hi {name},

Brands come to Dalai Llama looking for creators to make their videos: product ads, reels, explainers.

Join free, add a video or two, and brands can find you on Google and send you paid requests directly. No fees to join.',
    cta_label = 'Join Dalai Llama free',
    cta_url = 'https://creator.dalaillama.in',
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c2' AND updated_at = created_at;

INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url) VALUES
('00000000-0000-4000-8000-0000000000c3', 'New video ideas',
 '{name}, video ideas brands are asking for',
 'Hi {name},

Here are a few video ideas brands keep asking for right now:

1. A 15-second product reveal with a strong first second
2. "Three ways to use it", one product, three quick scenes
3. A founder story told in under 30 seconds

Make one on Dalai Llama and put it on your profile, so brands looking for exactly this can find you.',
 'Make a video', 'https://creator.dalaillama.in');
