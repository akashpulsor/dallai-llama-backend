-- V1__create_core_tables.sql
-- Ensure tables are created in dependency order (tenants first)

-- --- 1. Tenant Table (Master Data) ---
-- Matches the 'Tenant' model
CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,

    realm VARCHAR(255),
    deployment_model VARCHAR(64),
    region VARCHAR(64),
    status VARCHAR(64),

    ai_routing_enabled TINYINT(1) DEFAULT 0,
    ai_transcription_enabled TINYINT(1) DEFAULT 0,
    ai_noise_cancellation_enabled TINYINT(1) DEFAULT 0,

    kafka_username VARCHAR(255),
    kafka_password VARCHAR(255),

    redis_username VARCHAR(255),
    redis_password VARCHAR(255),

    postgres_username VARCHAR(255),
    postgres_password VARCHAR(255),

    mysql_username VARCHAR(255),
    mysql_password VARCHAR(255),

    kafka_reg_topic VARCHAR(255),
    kafka_call_topic VARCHAR(255),
    kafka_ai_result_topic VARCHAR(255),
    kafka_rtp_topic VARCHAR(255),

    kafka_bootstrap VARCHAR(255),
    redis_url VARCHAR(255),
    postgres_url VARCHAR(255),
    mysql_url VARCHAR(255),

    sip_udp_url VARCHAR(255),
    sip_tls_url VARCHAR(255),
    websocket_url VARCHAR(255),
    rtpengine_sock VARCHAR(255),
    pbx_core_url_internal VARCHAR(255) -- Added from model
);

-- --- 2. Signaling Configuration Table (References tenants) ---
-- Matches the 'SignalingConfig' model
CREATE TABLE IF NOT EXISTS signaling_configs (
    id VARCHAR(36) PRIMARY KEY, -- Changed to VARCHAR(36) to match model UUID generation
    tenant_id VARCHAR(36) NOT NULL, -- Changed to VARCHAR(36) to match tenants.id
    auth_realm VARCHAR(255),

    -- List fields (converted to text/JSON/separate table depending on JPA)
    -- For simplicity in MySQL, assuming JPA handles List<String> as a separate table
    -- For now, omitting dispatcher_targets, stun_urls, turn_urls as they are @ElementCollection

    turn_realm VARCHAR(255),
    turn_policy VARCHAR(255),
    turn_port_range VARCHAR(64),
    wss_url VARCHAR(255),

    sip_fqdn VARCHAR(255),  -- Added from model
    pbx_fqdn VARCHAR(255),  -- Added from model
    turn_fqdn VARCHAR(255), -- Added from model
    wss_fqdn VARCHAR(255),  -- Added from model

    sip_tls_secret VARCHAR(255),
    turn_tls_secret VARCHAR(255),
    wss_tls_secret VARCHAR(255),
    cert_issued_at DATETIME,
    cert_expires_at DATETIME,
    version INT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    enable_rtp_engine TINYINT(1) DEFAULT 1, -- Added from model
    enable_turn TINYINT(1) DEFAULT 1,       -- Added from model
    sbc_enabled TINYINT(1) DEFAULT 1,       -- Added from model
    ai_features_summary VARCHAR(255),       -- Added from model
    health_status VARCHAR(64),              -- Added from model

    UNIQUE KEY uk_tenant_config (tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- --- 3. Routing Policy Table (References tenants) ---
-- Matches the 'RoutingPolicy' model
CREATE TABLE IF NOT EXISTS routing_policies (
    id VARCHAR(36) PRIMARY KEY, -- Changed to VARCHAR(36) to match model UUID generation
    tenant_id VARCHAR(36) NOT NULL, -- Changed to VARCHAR(36) to match tenants.id
    entrypoint VARCHAR(255) NOT NULL, -- Added from model
    strategy VARCHAR(255) NOT NULL,   -- Added from model
    team_id VARCHAR(36),              -- Added from model

    -- List fields (skills, failover) are omitted here for schema simplicity,
    -- as JPA typically creates separate tables for @ElementCollection.

    ai_endpoint VARCHAR(255),
    ai_timeout_ms INT,
    version INT,
    updated_at DATETIME,

    UNIQUE KEY uk_tenant_entrypoint (tenant_id, entrypoint),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- --- 4. Call Detail Records (References tenants) ---
-- Matches the 'CallRecord' model (table name changed from call_record to call_records for model consistency)
CREATE TABLE IF NOT EXISTS call_records (
    id VARCHAR(36) PRIMARY KEY, -- Changed from BIGINT to VARCHAR(36) for UUID
    tenant_id VARCHAR(36) NOT NULL, -- Changed to VARCHAR(36) to match tenants.id
    call_id VARCHAR(255),

    caller VARCHAR(255),
    callee VARCHAR(255),
    record_path VARCHAR(255),

    duration_sec DOUBLE,        -- Changed from INT/duration_seconds to DOUBLE/duration_sec
    packet_loss DOUBLE,
    jitter DOUBLE,
    status VARCHAR(64),

    transcript_caller TEXT,
    transcript_responder TEXT,

    started_at DATETIME,
    ended_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    billed_amount DOUBLE,

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);


-- --- 5. Trunks Table (References tenants) ---
-- Matches the 'Trunk' model
CREATE TABLE IF NOT EXISTS trunks (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    sip_uri VARCHAR(255) NOT NULL,
    username VARCHAR(255),
    password VARCHAR(255),
    prefixes VARCHAR(255),
    priority INT NOT NULL,
    weight INT DEFAULT 1,
    region VARCHAR(64),
    enabled TINYINT(1) DEFAULT 1,
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- --- 6. DIDs Table (References tenants) ---
-- Matches the 'Did' model
CREATE TABLE IF NOT EXISTS dids (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    number VARCHAR(255) NOT NULL,
    entrypoint VARCHAR(255) NOT NULL,
    trunk_id VARCHAR(36),
    status VARCHAR(64),
    UNIQUE KEY uk_tenant_number (tenant_id, number),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);