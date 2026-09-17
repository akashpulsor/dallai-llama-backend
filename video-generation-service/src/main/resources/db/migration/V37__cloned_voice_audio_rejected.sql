-- A take a creator has listened to and does not want.
--
-- Every flow that puts "the dubbed voice" on a clip reaches for the most recent take, which is the
-- right rule -- re-dubbing is how a creator replaces a recording. But it leaves no way to say "not
-- that one" without recording another, and a take that came out wrong then sits as the newest thing
-- there is, waiting to be picked up by the next cut.
--
-- Rejecting marks it skipped. The row is kept rather than deleted: a creator who rejects and then
-- changes their mind should not have to pay for the synthesis again, and the text is evidence of
-- what was tried.
ALTER TABLE cloned_voice_audio
    ADD COLUMN rejected     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN rejected_at  TIMESTAMPTZ;

-- Take selection reads "newest, not rejected", per shot.
CREATE INDEX idx_cloned_voice_audio_usable
    ON cloned_voice_audio (shot_id, updated_at DESC) WHERE NOT rejected;
