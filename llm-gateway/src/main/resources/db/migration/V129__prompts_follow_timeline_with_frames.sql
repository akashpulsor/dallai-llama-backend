-- The prepared prompt follows the action timeline, with frame numbers.
--
-- VIDEO_MOTION_GRAPHIC_PROMPT v3: v2 never received the generation settings or the timeline, so a
-- motion-graphic shot's actions and second-by-second timeline were passed and ignored (its prompt
-- only said "it runs N seconds"). v3 is v2 with the generated duration, the frame rate and the
-- timeline added, and told to follow it interval by interval.
--
-- VIDEO_SHOT_PROMPT v6: v5 already follows the timeline; it now also writes the frame number with
-- each timestamp, as the timeline gives them.
--
-- Both are derived from the active text by replacing exact sentences, so nothing else in them
-- changes; a sentence that is not found stops the migration instead of shipping an unchanged copy.

DO $$
DECLARE
    mg_old  text := 'It runs {{durationSeconds}} seconds at {{fps}} frames per second.';
    mg_new  text := $t$It was planned to run {{durationSeconds}} seconds. It is generated for {{generationDurationSeconds}} seconds at {{generationFps}} frames per second.

THE TIMELINE
The approved timeline, with the frame each moment falls on. It is authoritative for timing and for what happens: every action in it appears, in its order, at its time, and the graphic ends on its final state.
{{sourceActionTimeline}}$t$;
    mg_old2 text := 'Anchor the timing to the duration -- what has happened by the halfway point, what lands last.';
    mg_new2 text := $t$Follow the timeline interval by interval, with explicit inline timestamps and their frame numbers as the timeline gives them (for example "by 0.6s (frame 18)"), covering the whole generated duration.$t$;
    sh_old  text := 'Use explicit inline timestamps.';
    sh_new  text := $t$Use explicit inline timestamps with their frame numbers, exactly as the timeline gives them (for example "by 0.6s (frame 18)").$t$;
    sh_old2 text := 'Describe what happens during each second.';
    sh_new2 text := 'Describe what happens in each interval of the timeline.';
    mg      text;
    sh      text;
BEGIN
    SELECT content INTO mg FROM prompt_template WHERE task_key = 'VIDEO_MOTION_GRAPHIC_PROMPT' AND version = 2;
    SELECT content INTO sh FROM prompt_template WHERE task_key = 'VIDEO_SHOT_PROMPT' AND version = 5;
    IF mg IS NULL OR position(mg_old IN mg) = 0 OR position(mg_old2 IN mg) = 0 THEN
        RAISE EXCEPTION 'VIDEO_MOTION_GRAPHIC_PROMPT v2 does not contain the sentences v3 replaces';
    END IF;
    IF sh IS NULL OR position(sh_old IN sh) = 0 OR position(sh_old2 IN sh) = 0 THEN
        RAISE EXCEPTION 'VIDEO_SHOT_PROMPT v5 does not contain the sentences v6 replaces';
    END IF;

    UPDATE prompt_template SET active = false WHERE task_key IN ('VIDEO_MOTION_GRAPHIC_PROMPT', 'VIDEO_SHOT_PROMPT') AND active = true;
    INSERT INTO prompt_template (task_key, version, content, active)
    VALUES ('VIDEO_MOTION_GRAPHIC_PROMPT', 3, replace(replace(mg, mg_old, mg_new), mg_old2, mg_new2), true),
           ('VIDEO_SHOT_PROMPT', 6, replace(replace(sh, sh_old, sh_new), sh_old2, sh_new2), true);
END $$;
