-- Sound layers: music or a sound effect the creator adds to a shot and places on the film's
-- timeline (e.g. a temple bell 1.5s into shot 12). Anchored to the shot, so reordering shots moves
-- the sound with it; offset_ms is from the shot's start and may run past its end. Mixed into the
-- film at render time only (FilmAssemblyService), never baked into a clip -- which is what makes
-- "included" a free switch: turning a layer off costs nothing but a re-render.
CREATE TABLE sound_layer (
    layer_id         UUID PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    project_id       UUID          NOT NULL,
    shot_id          UUID          NOT NULL,
    kind             VARCHAR(16)   NOT NULL,
    source           VARCHAR(16)   NOT NULL,
    prompt           TEXT,
    bucket           VARCHAR(200)  NOT NULL,
    object_key       VARCHAR(500)  NOT NULL,
    duration_seconds NUMERIC(8, 3),
    offset_ms        INTEGER       NOT NULL DEFAULT 0 CHECK (offset_ms >= 0),
    volume_db        NUMERIC(5, 2) NOT NULL,
    fade_in_ms       INTEGER       NOT NULL DEFAULT 0 CHECK (fade_in_ms >= 0),
    fade_out_ms      INTEGER       NOT NULL DEFAULT 0 CHECK (fade_out_ms >= 0),
    included         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by       UUID,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_sound_layer_project ON sound_layer (project_id);
