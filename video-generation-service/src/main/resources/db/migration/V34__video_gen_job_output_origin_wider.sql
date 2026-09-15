-- output_origin was varchar(16), which fits every value it was given except the one that mattered.
--
-- The repairs write "TAIL_" + the mode, and TAIL_REPLACE_AUDIO is eighteen characters. So putting a
-- dubbed take onto a clip did all of its work -- extracted, remuxed, uploaded the finished file --
-- and then failed on the row update, leaving the job pointing at the old clip and the creator
-- looking at an error after a repair that had actually succeeded.
--
-- Widened rather than trimmed: sixteen was an arbitrary guess in the first place, and a column that
-- records what produced a clip should have room for a name that says so.
ALTER TABLE video_gen_job ALTER COLUMN output_origin TYPE VARCHAR(32);
