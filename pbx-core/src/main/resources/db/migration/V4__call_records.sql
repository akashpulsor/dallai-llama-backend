-- V4__call_records.sql

CREATE TABLE call_records (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    call_id                 VARCHAR(100) NOT NULL,        -- SIP Call-ID
    direction               VARCHAR(10) NOT NULL,
    caller_number           VARCHAR(20),
    callee_number           VARCHAR(20),
    did_number              VARCHAR(20),
    agent_id                UUID,
    queue_id                UUID,
    product_code            VARCHAR(30),
    status                  VARCHAR(20),
    start_time              TIMESTAMP,
    answer_time             TIMESTAMP,
    end_time                TIMESTAMP,
    duration_seconds        INT DEFAULT 0,
    billable_seconds        INT DEFAULT 0,
    rate_per_minute         DECIMAL(10,4),
    cost                    DECIMAL(10,4),
    ai_minutes              DECIMAL(10,2) DEFAULT 0,
    recording_url           VARCHAR(500),
    sentiment_score         DECIMAL(3,2),
    transcript_summary      TEXT,
    hangup_cause            VARCHAR(50),
    metadata                JSONB DEFAULT '{}',
    created_at              TIMESTAMP DEFAULT NOW()
);

ALTER TABLE call_records ADD CONSTRAINT unique_call_id UNIQUE (call_id);