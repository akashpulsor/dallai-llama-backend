CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(50) NOT NULL UNIQUE,
    company_name VARCHAR(200),
    gstin VARCHAR(20),
    primary_contact_name VARCHAR(100) NOT NULL,
    primary_contact_email VARCHAR(255) NOT NULL,
    primary_contact_phone VARCHAR(20),
    country VARCHAR(2) NOT NULL DEFAULT 'IN',
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',

    -- Status
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    substatus VARCHAR(50),
    status_message VARCHAR(500),
    status_changed_at TIMESTAMP WITH TIME ZONE,

    -- Deployment
    deployment_model VARCHAR(20) NOT NULL DEFAULT 'shared',
    namespace VARCHAR(100),

    -- Keycloak
    keycloak_realm_name VARCHAR(100),
    keycloak_realm_id VARCHAR(100),
    keycloak_client_id VARCHAR(100),
    admin_user_id VARCHAR(100),
    admin_user_email VARCHAR(255),

    -- Plan (reference to Product Service)
    plan_id UUID,
    plan_code VARCHAR(50),
    plan_assigned_at TIMESTAMP WITH TIME ZONE,

    -- Billing (reference to Billing Service)
    wallet_id UUID,
    billing_state VARCHAR(20),
    billing_ready_at TIMESTAMP WITH TIME ZONE,

    -- Infrastructure URLs
    kafka_bootstrap VARCHAR(500),
    redis_url VARCHAR(500),
    postgres_url VARCHAR(500),
    mysql_url VARCHAR(500),
    kafka_reg_topic VARCHAR(255),
    kafka_call_topic VARCHAR(255),
    kafka_ai_result_topic VARCHAR(255),
    kafka_rtp_topic VARCHAR(255),

    -- SIP/Telecom
    sip_external_ip VARCHAR(50),
    sip_udp_url VARCHAR(255),
    sip_tls_url VARCHAR(255),
    turn_url VARCHAR(255),
    websocket_url VARCHAR(255),
    rtpengine_sock VARCHAR(255),

    -- DIDWW
    didww_trunk_id VARCHAR(100),
    didww_sip_config_id VARCHAR(100),

    -- Dashboard
    dashboard_url VARCHAR(255),

    -- Lifecycle timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMP WITH TIME ZONE,
    suspended_at TIMESTAMP WITH TIME ZONE,
    suspension_reason VARCHAR(500),
    deleted_at TIMESTAMP WITH TIME ZONE,

    -- Optimistic locking
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_status ON tenants(status);
CREATE INDEX idx_tenant_slug ON tenants(slug);
CREATE INDEX idx_tenant_email ON tenants(primary_contact_email);