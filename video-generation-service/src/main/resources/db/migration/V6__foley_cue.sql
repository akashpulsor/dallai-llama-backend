-- Cue sheet only -- no audio is generated here (see design doc §4.2/§6). Post-production reads
-- this table to know what foley/BGM to generate and where, without re-deriving it from ShotContext.
CREATE TABLE foley_cue (
    cue_id        BIGSERIAL PRIMARY KEY,
    prompt_id     UUID NOT NULL REFERENCES shot_prompt(prompt_id),
    timestamp_ms  INTEGER NOT NULL,
    cue_type      VARCHAR(32) NOT NULL, -- FOOTSTEP / DOOR / AMBIENT_BED / PRODUCT_SFX / HERO_SFX / MUSIC_BEAT
    description   TEXT NOT NULL
);

CREATE INDEX idx_foley_cue_prompt_timestamp ON foley_cue (prompt_id, timestamp_ms);
