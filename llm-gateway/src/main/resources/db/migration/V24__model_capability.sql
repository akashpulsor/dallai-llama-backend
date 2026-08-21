-- Real relational capability scoring for the generation-feasibility critic (Level 4 of the
-- pre-flight harness) -- not another key in model_master.capabilities' jsonb blob, a proper
-- typed table so a caller can query/join it like any other data.
CREATE TABLE model_capability (
    id BIGSERIAL PRIMARY KEY,
    model_id VARCHAR(128) NOT NULL REFERENCES model_master(model_id) ON DELETE CASCADE,
    capability_key VARCHAR(64) NOT NULL,
    strength VARCHAR(16) NOT NULL,
    CONSTRAINT uq_model_capability UNIQUE (model_id, capability_key)
);

CREATE INDEX idx_model_capability_model ON model_capability (model_id);

-- Seed values for seedance-v1, matching the worked example used to design this feature:
-- strong on camera motion and macro texture, weaker on rendered text and long continuous shots.
INSERT INTO model_capability (model_id, capability_key, strength) VALUES
('seedance-v1', 'CAMERA_MOTION', 'STRONG'),
('seedance-v1', 'OBJECT_CONSISTENCY', 'MEDIUM'),
('seedance-v1', 'TEXT_RENDERING', 'WEAK'),
('seedance-v1', 'MACRO_TEXTURE', 'STRONG'),
('seedance-v1', 'COMPLEX_PHYSICS', 'MEDIUM'),
('seedance-v1', 'LONG_CONTINUOUS_SHOTS', 'WEAK')
ON CONFLICT DO NOTHING;
