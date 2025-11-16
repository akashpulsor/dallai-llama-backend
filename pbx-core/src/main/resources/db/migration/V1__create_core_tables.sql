CREATE TABLE tenants (
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
    rtpengine_sock VARCHAR(255)
);

-- Signaling Configuration Table
CREATE TABLE IF NOT EXISTS signaling_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64),
    auth_realm VARCHAR(255),
    dispatcher_targets TEXT,
    turn_realm VARCHAR(255),
    turn_policy VARCHAR(255),
    turn_port_range VARCHAR(64),
    wss_url VARCHAR(255),
    stun_urls TEXT,
    turn_urls TEXT,
    version INT,
    sip_tls_secret VARCHAR(255),
    turn_tls_secret VARCHAR(255),
    wss_tls_secret VARCHAR(255),
    cert_issued_at DATETIME,
    cert_expires_at DATETIME,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

-- Routing Policy Table
CREATE TABLE IF NOT EXISTS routing_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64),
    rules JSON,
    priority INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

-- Call Detail Records (CDR)
CREATE TABLE IF NOT EXISTS call_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64),
    call_id VARCHAR(255),
    direction VARCHAR(32),
    duration_seconds INT,
    start_time DATETIME,
    end_time DATETIME,
    transcription TEXT,
    FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);
