-- Creator mail now goes as a plain personal letter (from Akash, no layout or pictures) to stay out of
-- Gmail Promotions; the starter templates read like one, without the sales words ("free", "no fees",
-- "paid") that also push mail there. Only where ops haven't changed them (still V61/V62's wording).
UPDATE ops_creator_mail_template SET
    name = 'Get found by brands',
    subject = '{name}, brands are looking for creators like you',
    body = 'Hi {name},

I''m Akash from Dalai Llama. Brands come to us, and find us on Google, when they need a creator to make their videos.

The creators they find are the ones with a profile and a video or two on it. Setting yours up takes a couple of minutes, and then brands can send you requests directly.',
    cta_label = 'Set up your profile here',
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c1' AND cta_label = 'Set up my free profile';

UPDATE ops_creator_mail_template SET
    subject = '{name}, making videos for brands?',
    body = 'Hi {name},

I''m Akash, I run Dalai Llama. Brands come to us looking for creators to make their videos: product ads, reels, explainers.

If you join and add a video or two, brands can find you on Google and send you requests directly. Joining costs nothing.',
    cta_label = 'Join Dalai Llama here',
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c2' AND cta_label = 'Join Dalai Llama free';

UPDATE ops_creator_mail_template SET
    subject = '{name}, a few video ideas for you',
    body = 'Hi {name},

A few videos brands have liked lately. Each one is an idea you could make for a brand of your own, and put on your profile so brands looking for exactly that can find you:',
    cta_label = 'Make your version on Dalai Llama',
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c3' AND subject = '{name}, video ideas brands are asking for';
