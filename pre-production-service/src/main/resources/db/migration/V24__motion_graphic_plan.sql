CREATE TABLE motion_graphic_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL UNIQUE REFERENCES shot(id),
    concept TEXT,
    on_screen_text TEXT,
    visual_style TEXT,
    animation_notes TEXT,
    duration_seconds INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
