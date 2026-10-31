-- Test mails are recorded too (status TEST), so their unsubscribe link works like a real one. A test
-- must not count as the template having reached the address, so "once per template" now ignores them.
ALTER TABLE ops_creator_mail_delivery DROP CONSTRAINT ops_creator_mail_delivery_once_uk;
CREATE UNIQUE INDEX ops_creator_mail_delivery_once_uk ON ops_creator_mail_delivery (template_id, email) WHERE status <> 'TEST';

-- A creator mail can carry up to three videos from the inspiration board, picked when it's sent.
ALTER TABLE ops_creator_mail_template ADD COLUMN idea_videos INT NOT NULL DEFAULT 0;
ALTER TABLE ops_creator_mail_template ADD CONSTRAINT ops_creator_mail_template_idea_videos_ck CHECK (idea_videos BETWEEN 0 AND 3);

-- The starter "New video ideas" (V61) shows real inspiration videos instead of a typed list, where
-- ops haven't edited it.
UPDATE ops_creator_mail_template SET
    body = 'Hi {name},

Here are a few videos brands love right now. Each one is an idea you could make for a brand of your own.

Make one on Dalai Llama and put it on your profile, so brands looking for exactly this can find you.',
    idea_videos = 3,
    updated_at = NOW()
WHERE id = '00000000-0000-4000-8000-0000000000c3' AND updated_at = created_at;
