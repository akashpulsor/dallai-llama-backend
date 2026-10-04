-- Follows V39 (already applied -- never edit an applied migration).

-- The previous shot's last frame is taken by post-production off the request thread. While it is
-- being taken the request id is set and the frame columns are empty; a failure leaves its reason.
ALTER TABLE shot_generation_plan
    ADD COLUMN continuation_request_id UUID,
    ADD COLUMN continuation_error      TEXT;

-- Generation controls: every step of the render path that can stop or reshape a shot is a
-- per-project switch, and the defaults are the path that lets a shot be generated.
--   fit_duration_to_dialogue    lengthen the clip to hold its measured line, up to the model's
--                               maximum. Never refuses. Off: the clip is generated at exactly the
--                               duration chosen.
--   auto_dub_dialogue           generate silent and lay the cloned voice on afterwards. Off: the
--                               model performs the line itself.
--   mix_background_music        lay the shot's music bed under the finished clip.
--   prevent_duplicate_renders   refuse to queue a shot already rendering, which would bill it twice.
--   attach_previous_last_frame  start every shot from the previous shot's last frame when it exists.
--   conform_to_planned_duration a clip generated at another length is brought to the planned length
--                               in post-production -- slowed when shorter, trimmed when longer.
--   interpolate_when_slowing    synthesise in-between frames when slowing, so motion stays smooth.
ALTER TABLE project_config
    ADD COLUMN fit_duration_to_dialogue    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN auto_dub_dialogue           BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN mix_background_music        BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN prevent_duplicate_renders   BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN attach_previous_last_frame  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN conform_to_planned_duration BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN interpolate_when_slowing    BOOLEAN NOT NULL DEFAULT true;

-- The shot's planned length beside the length actually rendered, and the post-production request
-- that conforms one to the other.
ALTER TABLE video_gen_job
    ADD COLUMN planned_duration_seconds INTEGER,
    ADD COLUMN conform_request_id       UUID;
