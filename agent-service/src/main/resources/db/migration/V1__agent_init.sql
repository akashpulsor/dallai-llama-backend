
CREATE TABLE agent_shifts (
  id serial PRIMARY KEY,
  agent_id bigint,
  start_at timestamp tz,
  end_at timestamp tz,
  timezone varchar(64)
);

CREATE TABLE agent_event_log (
  id serial PRIMARY KEY,
  agent_id bigint,
  event_type varchar(64),
  payload jsonb,
  created_at timestamp tz default now()
);

-- V1__initial_schema.sql
-- Agent Service Database Schema

-- Core Agent table
CREATE TABLE agents (
    id BIGSERIAL PRIMARY KEY,

    external_id VARCHAR(255) NOT NULL UNIQUE,
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    extension VARCHAR(255) NOT NULL,

    display_name VARCHAR(255),
    email VARCHAR(255),
    phone_number VARCHAR(50),

    tenant_id VARCHAR(128) NOT NULL,

    -- ENUMS
    status VARCHAR(50) NOT NULL,
    availability_status VARCHAR(50) NOT NULL,

    online BOOLEAN NOT NULL DEFAULT FALSE,
    available BOOLEAN NOT NULL DEFAULT TRUE,

    max_concurrent_calls INTEGER DEFAULT 1,

    skills JSONB DEFAULT '[]',
    queue_memberships JSONB DEFAULT '[]',
    preferences JSONB DEFAULT '{}',

    total_calls_handled INTEGER DEFAULT 0,
    total_call_duration_seconds BIGINT DEFAULT 0,
    average_handle_time_seconds INTEGER DEFAULT 0,

    last_call_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ
);

-- Indexes
CREATE INDEX idx_agents_tenant
    ON agents (tenant_id);

CREATE INDEX idx_agents_external_id
    ON agents (external_id);

CREATE INDEX idx_agents_status
    ON agents (tenant_id, status);

CREATE INDEX idx_agents_availability
    ON agents (tenant_id, availability_status);

-- Agent Sessions (tracks login/logout, shifts)
CREATE TABLE agent_sessions (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    session_id VARCHAR(255) UNIQUE NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,

    -- Session details
    login_time TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    logout_time TIMESTAMP TZ,
    expected_logout_time TIMESTAMP TZ,
    session_duration_seconds INTEGER,

    -- Connection info
    ip_address VARCHAR(45),
    user_agent TEXT,
    websocket_session_id VARCHAR(255),

    -- Status
    is_active BOOLEAN DEFAULT TRUE,
    disconnect_reason VARCHAR(255),

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_agent ON agent_sessions(agent_id);
CREATE INDEX idx_sessions_tenant ON agent_sessions(tenant_id);
CREATE INDEX idx_sessions_active ON agent_sessions(tenant_id, is_active);
CREATE INDEX idx_sessions_session_id ON agent_sessions(session_id);

-- Call Sessions (active calls being handled by agents)
CREATE TABLE call_sessions (
    id BIGSERIAL PRIMARY KEY,
    call_id VARCHAR(255) UNIQUE NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    agent_id BIGINT NOT NULL REFERENCES agents(id),
    agent_session_id BIGINT REFERENCES agent_sessions(id),

    -- Call details
    customer_number VARCHAR(50),
    customer_name VARCHAR(255),
    did_number VARCHAR(50), -- DID that received the call
    direction VARCHAR(20) NOT NULL, -- INBOUND, OUTBOUND
    queue_id VARCHAR(128),

    -- Status
    status VARCHAR(50) NOT NULL DEFAULT 'RINGING', -- RINGING, ANSWERED, ON_HOLD, TRANSFERRED, ENDED
    call_state VARCHAR(50), -- Additional state info

    -- Timing
    ring_start_time TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    answer_time TIMESTAMP TZ,
    hold_start_time TIMESTAMP TZ,
    end_time TIMESTAMP TZ,

    -- Durations (in seconds)
    ring_duration INTEGER,
    talk_duration INTEGER,
    hold_duration INTEGER,
    total_duration INTEGER,
    after_call_work_duration INTEGER,

    -- RTP & Media
    rtp_stream_id VARCHAR(255),
    media_server_id VARCHAR(255),
    codec VARCHAR(50),

    -- WebRTC
    webrtc_session_id VARCHAR(255),
    ice_connection_state VARCHAR(50),

    -- Metadata
    metadata JSONB DEFAULT '{}'::jsonb,
    tags JSONB DEFAULT '[]'::jsonb,

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_call_sessions_call_id ON call_sessions(call_id);
CREATE INDEX idx_call_sessions_tenant ON call_sessions(tenant_id);
CREATE INDEX idx_call_sessions_agent ON call_sessions(agent_id);
CREATE INDEX idx_call_sessions_status ON call_sessions(tenant_id, status);
CREATE INDEX idx_call_sessions_active ON call_sessions(tenant_id, agent_id, status) WHERE status IN ('RINGING', 'ANSWERED', 'ON_HOLD');
CREATE INDEX idx_call_sessions_queue ON call_sessions(tenant_id, queue_id);

-- Agent Skills
CREATE TABLE agent_skills (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,
    skill_name VARCHAR(255) NOT NULL,
    skill_level INTEGER DEFAULT 1 CHECK (skill_level >= 1 AND skill_level <= 10),
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),

    CONSTRAINT agent_skills_unique UNIQUE(agent_id, skill_name)
);

CREATE INDEX idx_agent_skills_agent ON agent_skills(agent_id);
CREATE INDEX idx_agent_skills_skill ON agent_skills(tenant_id, skill_name);
CREATE INDEX idx_agent_skills_level ON agent_skills(tenant_id, skill_name, skill_level);

-- Queues (virtual queues agents belong to)
CREATE TABLE queues (
    id BIGSERIAL PRIMARY KEY,
    queue_id VARCHAR(128) UNIQUE NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    queue_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),

    -- Queue configuration
    max_wait_time_seconds INTEGER DEFAULT 300,
    max_queue_size INTEGER DEFAULT 100,
    priority INTEGER DEFAULT 5,
    routing_strategy VARCHAR(50) DEFAULT 'LONGEST_IDLE', -- ROUND_ROBIN, LONGEST_IDLE, SKILL_BASED, LEAST_ACTIVE

    -- Skills required for this queue
    required_skills JSONB DEFAULT '[]'::jsonb,

    -- Status
    is_active BOOLEAN DEFAULT TRUE,

    -- Metadata
    metadata JSONB DEFAULT '{}'::jsonb,

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),

    CONSTRAINT queues_tenant_name_unique UNIQUE(tenant_id, queue_name)
);

CREATE INDEX idx_queues_tenant ON queues(tenant_id);
CREATE INDEX idx_queues_active ON queues(tenant_id, is_active);

-- Queue Memberships (many-to-many: agents <-> queues)
CREATE TABLE queue_memberships (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    queue_id BIGINT NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,

    priority INTEGER DEFAULT 5,
    is_active BOOLEAN DEFAULT TRUE,

    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),

    CONSTRAINT queue_memberships_unique UNIQUE(agent_id, queue_id)
);

CREATE INDEX idx_queue_memberships_agent ON queue_memberships(agent_id);
CREATE INDEX idx_queue_memberships_queue ON queue_memberships(queue_id);
CREATE INDEX idx_queue_memberships_active ON queue_memberships(tenant_id, queue_id, is_active);

-- Agent Shifts (scheduled work periods)
CREATE TABLE agent_shifts (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,

    -- Shift timing
    scheduled_start_at TIMESTAMP TZ NOT NULL,
    scheduled_end_at TIMESTAMP TZ NOT NULL,
    actual_start_at TIMESTAMP TZ,
    actual_end_at TIMESTAMP TZ,

    -- Shift details
    shift_type VARCHAR(50) DEFAULT 'REGULAR', -- REGULAR, OVERTIME, ON_CALL, TRAINING
    status VARCHAR(50) DEFAULT 'SCHEDULED', -- SCHEDULED, ACTIVE, COMPLETED, CANCELLED
    timezone VARCHAR(64) DEFAULT 'UTC',

    -- Break times
    breaks JSONB DEFAULT '[]'::jsonb,

    -- Notes
    notes TEXT,

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shifts_agent ON agent_shifts(agent_id);
CREATE INDEX idx_shifts_tenant ON agent_shifts(tenant_id);
CREATE INDEX idx_shifts_scheduled ON agent_shifts(tenant_id, scheduled_start_at, scheduled_end_at);
CREATE INDEX idx_shifts_status ON agent_shifts(tenant_id, status);
CREATE INDEX idx_shifts_active ON agent_shifts(tenant_id, agent_id, status) WHERE status = 'ACTIVE';

-- Agent Event Log (audit trail of all agent actions)
CREATE TABLE agent_event_log (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT REFERENCES agents(id) ON DELETE SET NULL,
    agent_session_id BIGINT REFERENCES agent_sessions(id) ON DELETE SET NULL,
    call_session_id BIGINT REFERENCES call_sessions(id) ON DELETE SET NULL,
    tenant_id VARCHAR(128) NOT NULL,

    -- Event details
    event_type VARCHAR(128) NOT NULL,
    event_category VARCHAR(50), -- LOGIN, LOGOUT, CALL, STATUS_CHANGE, BREAK, TRAINING
    severity VARCHAR(20) DEFAULT 'INFO', -- INFO, WARNING, ERROR

    -- Event payload
    payload JSONB,
    message TEXT,

    -- Context
    ip_address VARCHAR(45),
    user_agent TEXT,

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_event_log_agent ON agent_event_log(agent_id);
CREATE INDEX idx_event_log_tenant ON agent_event_log(tenant_id);
CREATE INDEX idx_event_log_type ON agent_event_log(tenant_id, event_type);
CREATE INDEX idx_event_log_category ON agent_event_log(event_category);
CREATE INDEX idx_event_log_created ON agent_event_log(created_at);
CREATE INDEX idx_event_log_call_session ON agent_event_log(call_session_id);

-- Call Recordings Metadata
CREATE TABLE call_recordings (
    id BIGSERIAL PRIMARY KEY,
    call_session_id BIGINT NOT NULL REFERENCES call_sessions(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,

    -- Recording details
    recording_id VARCHAR(255) UNIQUE NOT NULL,
    storage_path VARCHAR(1024),
    storage_type VARCHAR(50) DEFAULT 'S3', -- S3, LOCAL, NFS
    file_size_bytes BIGINT,
    duration_seconds INTEGER,
    format VARCHAR(20) DEFAULT 'WAV', -- WAV, MP3, OGG

    -- Status
    status VARCHAR(50) DEFAULT 'RECORDING', -- RECORDING, COMPLETED, FAILED, DELETED

    -- Timestamps
    recording_start_time TIMESTAMP TZ,
    recording_end_time TIMESTAMP TZ,
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recordings_call ON call_recordings(call_session_id);
CREATE INDEX idx_recordings_tenant ON call_recordings(tenant_id);
CREATE INDEX idx_recordings_status ON call_recordings(status);

-- Call Transcripts
CREATE TABLE call_transcripts (
    id BIGSERIAL PRIMARY KEY,
    call_session_id BIGINT NOT NULL REFERENCES call_sessions(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,

    -- Transcript details
    speaker VARCHAR(50), -- AGENT, CUSTOMER
    text TEXT NOT NULL,
    confidence DECIMAL(5,4),

    -- Timing
    start_time_ms BIGINT,
    end_time_ms BIGINT,

    -- Metadata
    language VARCHAR(10) DEFAULT 'en-US',
    sentiment VARCHAR(20), -- POSITIVE, NEGATIVE, NEUTRAL
    sentiment_score DECIMAL(5,4),

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transcripts_call ON call_transcripts(call_session_id);
CREATE INDEX idx_transcripts_tenant ON call_transcripts(tenant_id);
CREATE INDEX idx_transcripts_speaker ON call_transcripts(call_session_id, speaker);

-- Agent Performance Metrics (aggregated)
CREATE TABLE agent_performance_metrics (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id VARCHAR(128) NOT NULL,

    -- Time period
    metric_date DATE NOT NULL,
    metric_hour INTEGER, -- NULL for daily aggregation, 0-23 for hourly

    -- Call metrics
    total_calls INTEGER DEFAULT 0,
    inbound_calls INTEGER DEFAULT 0,
    outbound_calls INTEGER DEFAULT 0,
    answered_calls INTEGER DEFAULT 0,
    missed_calls INTEGER DEFAULT 0,

    -- Duration metrics (in seconds)
    total_talk_time INTEGER DEFAULT 0,
    total_hold_time INTEGER DEFAULT 0,
    total_after_call_work_time INTEGER DEFAULT 0,
    total_idle_time INTEGER DEFAULT 0,

    -- Performance metrics
    average_handle_time INTEGER,
    average_talk_time INTEGER,
    first_call_resolution_count INTEGER DEFAULT 0,
    transferred_calls_count INTEGER DEFAULT 0,

    -- Quality metrics
    customer_satisfaction_score DECIMAL(3,2),
    quality_score DECIMAL(3,2),

    -- Availability
    total_available_time_seconds INTEGER DEFAULT 0,
    total_unavailable_time_seconds INTEGER DEFAULT 0,
    total_break_time_seconds INTEGER DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP TZ NOT NULL DEFAULT NOW(),

    CONSTRAINT agent_metrics_unique UNIQUE(agent_id, metric_date, metric_hour)
);

CREATE INDEX idx_performance_agent ON agent_performance_metrics(agent_id);
CREATE INDEX idx_performance_tenant ON agent_performance_metrics(tenant_id);
CREATE INDEX idx_performance_date ON agent_performance_metrics(metric_date);
CREATE INDEX idx_performance_agent_date ON agent_performance_metrics(agent_id, metric_date);

-- Triggers for updated_at timestamps
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_agents_updated_at BEFORE UPDATE ON agents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_agent_sessions_updated_at BEFORE UPDATE ON agent_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_call_sessions_updated_at BEFORE UPDATE ON call_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_agent_skills_updated_at BEFORE UPDATE ON agent_skills
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_queues_updated_at BEFORE UPDATE ON queues
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_queue_memberships_updated_at BEFORE UPDATE ON queue_memberships
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_agent_shifts_updated_at BEFORE UPDATE ON agent_shifts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
