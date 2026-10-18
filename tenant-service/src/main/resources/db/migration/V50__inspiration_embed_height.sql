-- The pin's height in Pinterest's player at its 450px width (Pinterest's oEmbed "height"), so the
-- board can size each card to its video, Pinterest-style. Null: shown as a tall vertical video.
ALTER TABLE inspiration_item ADD COLUMN embed_height INT;
ALTER TABLE inspiration_item ADD CONSTRAINT inspiration_item_embed_height_ck CHECK (embed_height BETWEEN 100 AND 2000);
