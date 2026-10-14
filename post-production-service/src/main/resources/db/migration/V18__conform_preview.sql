-- Whether a conformed cut becomes the shot's clip as soon as it is made. The automatic finishing
-- after a render (trim to the shot, lay the recorded take) does; a slow-down the creator asks for
-- from the clip's tools is a version they preview and choose, like a dubbed or silent cut.
ALTER TABLE shot_clip_conform ADD COLUMN activate BOOLEAN NOT NULL DEFAULT true;
