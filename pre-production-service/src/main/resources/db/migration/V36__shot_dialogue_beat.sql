-- A "beat" is a dialogue timestamp -- the specific second within a shot's timeline where a line
-- starts and how long it runs. A shot's dialogue can have more than one (multiple lines within one
-- shot, "clone in parts"). This is genuinely new data -- Shot.scriptLine/voiceOver is a single
-- free-text field with no timing; the "hook beat plan" generated during script writing
-- (ScriptGenerationService.generateHookBeatPlan) is a prose-structuring aid only, never persisted,
-- not reusable here.
CREATE TABLE shot_dialogue_beat (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    start_seconds NUMERIC(6,2) NOT NULL,
    duration_seconds NUMERIC(6,2) NOT NULL,
    text TEXT NOT NULL,
    character_key VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shot_dialogue_beat_shot ON shot_dialogue_beat(shot_id, order_index);
