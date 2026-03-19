-- V8__campaigns_bots.sql

-- ═══════════════════════════════════════════════════
-- BOTS — AI conversation config (reusable across campaigns)
-- ═══════════════════════════════════════════════════
CREATE TABLE bots (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    status                  VARCHAR(20) DEFAULT 'DRAFT',    -- DRAFT, ACTIVE, DISABLED

    -- LLM Configuration
    system_prompt           TEXT NOT NULL,                   -- The core LLM system prompt
    greeting_message        TEXT,                            -- First thing bot says
    goodbye_message         TEXT,
    guidelines              JSONB DEFAULT '[]',              -- ["Do not discuss pricing", "Always confirm name"]
    allowed_intents         JSONB DEFAULT '[]',              -- ["appointment", "billing", "support"]
    fallback_message        TEXT,                            -- When bot doesn't understand

    -- Escalation Rules
    escalation_rules        JSONB DEFAULT '{}',              -- {"maxFailedTurns": 3, "keywords": ["speak to agent"], "sentimentThreshold": -0.5}
    transfer_target         VARCHAR(200),                    -- Queue UUID, agent extension, or external number
    transfer_type           VARCHAR(20) DEFAULT 'QUEUE',     -- QUEUE, AGENT, EXTERNAL, VOICEMAIL

    -- Voice Configuration
    voice_provider          VARCHAR(30) DEFAULT 'piper',     -- piper, elevenlabs, openai
    voice_id                VARCHAR(100),                    -- Provider-specific voice ID
    voice_speed             DECIMAL(3,2) DEFAULT 1.00,
    language                VARCHAR(10) DEFAULT 'en',

    -- Behavior
    max_turns               INT DEFAULT 30,
    max_duration_seconds    INT DEFAULT 600,
    dtmf_enabled            BOOLEAN DEFAULT TRUE,
    barge_in_enabled        BOOLEAN DEFAULT TRUE,            -- Caller can interrupt bot
    sentiment_tracking      BOOLEAN DEFAULT FALSE,
    transcript_enabled      BOOLEAN DEFAULT TRUE,

    -- Knowledge Base (RAG)
    knowledge_base_id       UUID,                            -- Reference to external KB
    rag_enabled             BOOLEAN DEFAULT FALSE,

    -- Custom Data (arbitrary bot-specific params)
    custom_data             JSONB DEFAULT '{}',

    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- ═══════════════════════════════════════════════════
-- CAMPAIGNS — Inbound or Outbound, links to a bot
-- ═══════════════════════════════════════════════════
CREATE TABLE campaigns (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             TEXT,
    campaign_type           VARCHAR(20) NOT NULL,             -- INBOUND, OUTBOUND
    status                  VARCHAR(20) DEFAULT 'DRAFT',     -- DRAFT, SCHEDULED, RUNNING, PAUSED, COMPLETED, CANCELLED

    -- Bot assignment
    bot_id                  UUID REFERENCES bots(id),

    -- DID / Caller ID
    did_number              VARCHAR(20),                     -- For inbound: the DID that triggers this campaign
    outbound_caller_id      VARCHAR(20),                     -- For outbound: shown to callee

    -- Outbound Dialer Config
    dialer_mode             VARCHAR(20),                     -- PROGRESSIVE, PREDICTIVE, PREVIEW (null for inbound)
    pacing_ratio            DECIMAL(3,2) DEFAULT 1.00,       -- Predictive: calls per agent (1.2 = 20% over-dial)
    max_concurrent_calls    INT DEFAULT 5,                   -- Outbound max simultaneous dials
    max_attempts_per_contact INT DEFAULT 3,
    retry_delay_minutes     INT DEFAULT 60,                  -- Wait between retries
    amd_enabled             BOOLEAN DEFAULT FALSE,           -- Answering Machine Detection
    amd_action              VARCHAR(20) DEFAULT 'HANGUP',    -- HANGUP, LEAVE_VM, CONNECT

    -- Inbound Config
    queue_id                UUID,                            -- Route to queue after bot (optional)
    after_hours_action      VARCHAR(20) DEFAULT 'VOICEMAIL', -- VOICEMAIL, BOT, QUEUE, HANGUP
    after_hours_bot_id      UUID,                            -- Different bot for after hours

    -- Schedule
    timezone                VARCHAR(50) DEFAULT 'Asia/Kolkata',
    start_date              DATE,
    end_date                DATE,

    -- Limits
    max_daily_calls         INT,                             -- Outbound daily cap
    max_total_calls         INT,                             -- Campaign lifetime cap

    -- Stats (denormalized for fast reads)
    total_contacts          INT DEFAULT 0,
    contacts_dialed         INT DEFAULT 0,
    contacts_connected      INT DEFAULT 0,
    contacts_completed      INT DEFAULT 0,

    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW(),
    started_at              TIMESTAMP,
    completed_at            TIMESTAMP,
    UNIQUE(tenant_id, name)
);

-- ═══════════════════════════════════════════════════
-- CAMPAIGN SCHEDULES — Time windows for outbound dialing
-- ═══════════════════════════════════════════════════
CREATE TABLE campaign_schedules (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id             UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    day_of_week             INT NOT NULL,                    -- 1=Mon, 7=Sun (ISO)
    start_time              TIME NOT NULL,                   -- 09:00
    end_time                TIME NOT NULL,                   -- 18:00
    UNIQUE(campaign_id, day_of_week)
);

-- ═══════════════════════════════════════════════════
-- CAMPAIGN CONTACTS — Outbound dial list / leads
-- ═══════════════════════════════════════════════════
CREATE TABLE campaign_contacts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id             UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    tenant_id               UUID NOT NULL,

    -- Contact Info
    phone_number            VARCHAR(20) NOT NULL,
    name                    VARCHAR(100),
    email                   VARCHAR(100),
    company                 VARCHAR(100),
    custom_data             JSONB DEFAULT '{}',              -- Arbitrary lead fields

    -- Dialing State
    status                  VARCHAR(20) DEFAULT 'PENDING',   -- PENDING, DIALING, CONNECTED, NO_ANSWER, BUSY, FAILED, DNC, COMPLETED, SKIPPED
    attempt_count           INT DEFAULT 0,
    last_attempt_at         TIMESTAMP,
    next_attempt_at         TIMESTAMP,
    completed_at            TIMESTAMP,

    -- Call Result
    call_id                 VARCHAR(100),                    -- Links to call_records
    duration_seconds        INT,
    disposition             VARCHAR(50),                     -- INTERESTED, NOT_INTERESTED, CALLBACK, WRONG_NUMBER, etc.
    notes                   TEXT,

    -- Priority
    priority                INT DEFAULT 0,                   -- Higher = dial first
    assigned_agent_id       UUID,                            -- For PREVIEW mode

    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- DNC — Do-Not-Call list (tenant-scoped)
-- ═══════════════════════════════════════════════════
CREATE TABLE dnc_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    phone_number            VARCHAR(20) NOT NULL,
    reason                  VARCHAR(100),                    -- OPT_OUT, REGULATORY, MANUAL
    source                  VARCHAR(50),                     -- API, CSV_UPLOAD, CALLER_REQUEST, REGULATORY
    added_at                TIMESTAMP DEFAULT NOW(),
    expires_at              TIMESTAMP,                       -- NULL = permanent
    UNIQUE(tenant_id, phone_number)
);

-- ═══════════════════════════════════════════════════
-- INDEXES
-- ═══════════════════════════════════════════════════
CREATE INDEX idx_bots_tenant ON bots(tenant_id);
CREATE INDEX idx_bots_status ON bots(tenant_id, status);
CREATE INDEX idx_campaigns_tenant ON campaigns(tenant_id);
CREATE INDEX idx_campaigns_status ON campaigns(tenant_id, status);
CREATE INDEX idx_campaigns_did ON campaigns(did_number);
CREATE INDEX idx_campaigns_type ON campaigns(tenant_id, campaign_type);
CREATE INDEX idx_contacts_campaign ON campaign_contacts(campaign_id, status);
CREATE INDEX idx_contacts_phone ON campaign_contacts(campaign_id, phone_number);
CREATE INDEX idx_contacts_next_attempt ON campaign_contacts(campaign_id, status, next_attempt_at)
    WHERE status IN ('PENDING', 'NO_ANSWER', 'BUSY');
CREATE INDEX idx_contacts_priority ON campaign_contacts(campaign_id, priority DESC, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_dnc_phone ON dnc_entries(tenant_id, phone_number);
CREATE INDEX idx_dnc_expires ON dnc_entries(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_campaign_schedules ON campaign_schedules(campaign_id);