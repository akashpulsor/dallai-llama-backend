-- Free-text tag on each uploaded multi-image asset.
--
-- These are NOT the same thing as the storyboard/production reference frames. A reference frame
-- is loose guidance ("here is roughly the look"). A tagged asset is the REAL artwork the finished
-- video must contain verbatim -- the actual logo, the actual app screens, the actual product
-- pack. The tag is the handle the video prompt uses: with tag='logo' the prompt can say "the
-- logo" and the model knows precisely which attached image that refers to, instead of inventing
-- a logo. Same for 'app home screen', 'product pack front', a character's real photo, etc.
--
-- A tag groups images: the creator uploads one image or a set of images in a single action and
-- gives that upload a tag, so "app flow" can legitimately be four screenshots sharing one tag
-- while "logo" is a single image. Grouping is therefore just "rows on this shot sharing a tag" --
-- no separate group table, and re-uploading under an existing tag extends that group.
--
-- Nullable: rows uploaded before this migration have no tag. The prompt composer falls back to
-- the shot's multiImageLabel for those, so nothing that already works breaks.
ALTER TABLE shot_reference_image
    ADD COLUMN IF NOT EXISTS tag VARCHAR(120);

-- Reading a shot's assets always groups by tag then orders within the group, so index the pair
-- the composer actually sorts on.
CREATE INDEX IF NOT EXISTS idx_shot_reference_image_shot_tag_ordinal
    ON shot_reference_image (shot_id, tag, ordinal);
