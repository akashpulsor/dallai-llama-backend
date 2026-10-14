-- Four generation switches are retired; the code no longer reads or writes them.
--
--   fit_duration_to_dialogue    the clip is the length the creator chose in the video studio;
--                               a line too long for it is fixed by extending the shot or
--                               rephrasing the line, never by a silent resize at render.
--   auto_dub_dialogue           a shot with a line is always rendered silent and gets the take the
--                               creator recorded -- never a voice the video model invents, never a
--                               fresh synthesis at render.
--   mix_background_music        was never read: music is a sound layer, mixed when the film renders.
--   conform_to_planned_duration slowing a shorter clip to the shot is a step the creator takes after
--   interpolate_when_slowing    generation, and it always interpolates.
--
-- Given defaults rather than dropped, so the previous release keeps working while this one rolls
-- out (it still selects them). A later migration drops them.
ALTER TABLE shot_generation_controls
    ALTER COLUMN fit_duration_to_dialogue    SET DEFAULT false,
    ALTER COLUMN auto_dub_dialogue           SET DEFAULT true,
    ALTER COLUMN mix_background_music        SET DEFAULT true,
    ALTER COLUMN conform_to_planned_duration SET DEFAULT true,
    ALTER COLUMN interpolate_when_slowing    SET DEFAULT true;
