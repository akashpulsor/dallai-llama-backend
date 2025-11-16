ALTER TABLE signaling_config
  ADD COLUMN enable_rtp_engine BOOLEAN DEFAULT TRUE,
  ADD COLUMN enable_turn BOOLEAN DEFAULT TRUE,
  ADD COLUMN sbc_enabled BOOLEAN DEFAULT TRUE,
  ADD COLUMN ai_features_summary VARCHAR(255),
  ADD COLUMN health_status VARCHAR(64);
