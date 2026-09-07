-- Shot.emotion, captured at generate() time alongside the rest of the beat snapshot (same
-- reasoning as voice_reference_url/builtin_voice_id) so approve()'s later dub call still has it.
ALTER TABLE video_gen_job_dialogue_beat ADD COLUMN emotion VARCHAR(240);
