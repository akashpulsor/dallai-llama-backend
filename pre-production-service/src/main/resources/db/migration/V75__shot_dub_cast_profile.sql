-- Who reads this shot's line, as a character from the cast rather than a raw voice: the dub then
-- uses that character's own voice -- cloned, cloned from their sample, or built in -- resolved the
-- same way as for any shot they appear in. Null: the shot's own character, else the narrator.
-- dub_voice_id (a specific built-in voice) still wins when both are set.
ALTER TABLE shot ADD COLUMN dub_cast_profile_id UUID;
