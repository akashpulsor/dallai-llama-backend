-- Two things a cut needs to say about itself that it could not before.
--
-- 1. WHETHER IT IS OUT BEING EDITED.
--    A creator downloads a cut, opens it in their own editor, and brings it back. Until now only the
--    second half left a trace: you could see which shots had an uploaded version, never which ones
--    were out. So "what am I still waiting on" had no answer, and a shot someone took away on Friday
--    looked identical to one nobody had touched.
--
--    edited_from_version_id closes the loop from the other end -- an uploaded cut says which cut it
--    was made from, so a version that went out and came back is a chain rather than two unrelated
--    rows.
--
-- 2. WHETHER THE CLIENT MAY WATCH IT.
--    Publishing was all-or-nothing on the whole film. But a creator often wants one shot in front of
--    a client -- the one they are unsure about -- long before the film exists, and waiting until
--    every shot is generated to ask a single question is the wrong shape. A shot is publishable on
--    its own terms, with the same rule as the film: off means invisible, not merely undownloadable.
ALTER TABLE shot_clip_version
    ADD COLUMN downloaded_for_edit_at  TIMESTAMPTZ,
    ADD COLUMN downloaded_for_edit_by  UUID,
    -- The cut this one was edited from. Null for everything that was not an upload.
    ADD COLUMN edited_from_version_id  UUID,
    ADD COLUMN published               BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN published_at            TIMESTAMPTZ;

-- The client review page's read: the shots of this project a creator has chosen to show. Partial,
-- because published is the only value it ever asks for and the rest of the table is far larger.
CREATE INDEX idx_shot_clip_version_published
    ON shot_clip_version (project_id, shot_id) WHERE published;

-- "What is still out being edited" -- the question that had no answer.
CREATE INDEX idx_shot_clip_version_out_for_edit
    ON shot_clip_version (project_id) WHERE downloaded_for_edit_at IS NOT NULL;
