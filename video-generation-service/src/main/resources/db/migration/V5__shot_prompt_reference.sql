CREATE TABLE shot_prompt_reference (
    ref_id      BIGSERIAL PRIMARY KEY,
    prompt_id   UUID NOT NULL REFERENCES shot_prompt(prompt_id),
    ref_kind    VARCHAR(32) NOT NULL,  -- CHARACTER_FACE / SET / STORYBOARD / STYLE_ANCHOR / PRIOR_SHOT_LAST_FRAME / PRODUCT_HERO
    bucket      VARCHAR(128) NOT NULL,
    object_key  VARCHAR(1024) NOT NULL,
    slot_index  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_shot_prompt_reference_prompt ON shot_prompt_reference (prompt_id);
