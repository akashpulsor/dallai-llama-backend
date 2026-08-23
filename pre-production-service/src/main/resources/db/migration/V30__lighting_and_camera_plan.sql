CREATE TABLE lighting_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL UNIQUE REFERENCES shot(id),
    cinematic_intent TEXT,
    estimated_setup_minutes INTEGER,
    key_light_gear VARCHAR(200),
    fill_light_gear VARCHAR(200),
    rim_light_gear VARCHAR(200),
    neg_fill_gear VARCHAR(200),
    diffuser_gear VARCHAR(200),
    camera_rig_gear VARCHAR(200),
    build_steps TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE camera_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL UNIQUE REFERENCES shot(id),
    blocking_map TEXT,
    execution_steps TEXT,
    gimbal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    gimbal_device VARCHAR(120),
    gimbal_mode VARCHAR(80),
    gimbal_pan_speed VARCHAR(40),
    gimbal_tilt_speed VARCHAR(40),
    safety_flags TEXT,
    requires_coordinator BOOLEAN NOT NULL DEFAULT FALSE,
    compliance_note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
