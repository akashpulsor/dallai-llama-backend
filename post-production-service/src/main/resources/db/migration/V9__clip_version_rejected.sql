-- TURNING DOWN A CUT.
--
-- A dubbed cut -- native audio muted, the cloned take muxed on in its place -- arrives as a PREVIEW
-- and sits in the shot's list as the newest thing there is. Until now the only answers to one that
-- came out wrong were to accept it anyway or to make another, and making another leaves the bad one
-- still newest. The same gap the spoken-take reject closed one layer down, where a recording that
-- came out wrong stayed the newest take and every cut made afterwards picked it up.
--
-- Rejecting does NOT remove anything. The row is kept and the cut stays watchable, so a rejection
-- can be taken back and a creator can still see what they turned down. Nor does it touch the film:
-- the ACTIVE cut stays exactly as it was, and accept remains the only thing that changes which cut
-- the film uses.
ALTER TABLE shot_clip_version
    ADD COLUMN rejected    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN rejected_at TIMESTAMPTZ;

-- "Which cuts of this shot are still in play" -- the read every panel does, and the one that must
-- stop reaching for a cut that has been turned down. Partial because rejected cuts are the rare
-- case and the rest of the table is far larger.
CREATE INDEX idx_shot_clip_version_rejected
    ON shot_clip_version (shot_id) WHERE rejected;
