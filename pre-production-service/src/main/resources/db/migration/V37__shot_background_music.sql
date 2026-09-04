-- One current background-music track per shot -- on-demand generation only, from the shot's
-- already-planned ambient_bed sound design (see ShotBackgroundMusicService).
CREATE TABLE shot_background_music (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL UNIQUE REFERENCES shot(id) ON DELETE CASCADE,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    prompt TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
