-- Per-shot dub voice override.
--
-- Who speaks a shot has been decided entirely by the cast: the shot's primary character resolves
-- to a cast profile, and that profile's voice is used -- or, for a shot with no character of its
-- own, the project's narrator. That is the right default and stays the default. What it left no
-- room for is a single shot that should be said by someone else, which on this film is every shot
-- the creator wanted to try a different read on: the only way to change it was to change the cast
-- profile, which moves every other shot that character speaks in.
--
-- NULL means "use the cast", which is what every existing row wants and gets without a backfill.
ALTER TABLE shot ADD COLUMN dub_voice_id VARCHAR(128);

COMMENT ON COLUMN shot.dub_voice_id IS
    'Provider voice id to dub THIS shot with, overriding the cast profile. NULL = use the cast.';
