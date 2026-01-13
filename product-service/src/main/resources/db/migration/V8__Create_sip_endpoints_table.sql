CREATE TABLE sip_endpoints (
    id UUID PRIMARY KEY,
    did_id UUID NOT NULL REFERENCES dids(id),

    -- Credentials for Kamailio
    username VARCHAR(100) NOT NULL, -- e.g., did_919876543210
    password_hash VARCHAR(255) NOT NULL, -- HA1 hash: MD5(username:realm:password)
    domain VARCHAR(100) NOT NULL, -- e.g., sip.dalaillama.in
    realm VARCHAR(100) NOT NULL, -- e.g., dalaillama.in

    -- Status
    active BOOLEAN DEFAULT true,
    registered_in_kamailio BOOLEAN DEFAULT false,
    kamailio_synced_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_endpoint_username ON sip_endpoints(username, domain);
CREATE INDEX idx_endpoint_did ON sip_endpoints(did_id);
CREATE INDEX idx_endpoint_pending_sync ON sip_endpoints(registered_in_kamailio) WHERE registered_in_kamailio = false;