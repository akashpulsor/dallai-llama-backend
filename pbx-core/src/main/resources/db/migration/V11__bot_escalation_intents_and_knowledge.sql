-- V11__bot_escalation_intents_and_knowledge.sql
--
-- Two new tables for AI bot intelligence:
--   1. bot_escalation_intents — per-bot configurable intent thresholds
--   2. bot_knowledge_documents — RAG knowledge base per bot

-- ════════════════════════════════════════════════════════════
-- BOT ESCALATION INTENTS
--
-- Admin defines: "When caller shows intent X with confidence > Y → do Z"
-- voice-brain reads these at call start and checks after every LLM response.
--
-- Example rows:
--   bot_id=abc, intent=interested,       threshold=0.85, action=TRANSFER_QUEUE, target=sales
--   bot_id=abc, intent=ready_to_buy,     threshold=0.90, action=TRANSFER_AGENT, target=5001
--   bot_id=abc, intent=not_interested,   threshold=0.95, action=HANGUP,         target=null
--   bot_id=abc, intent=appointment_made, threshold=0.90, action=HANGUP,         target=null
-- ════════════════════════════════════════════════════════════

CREATE TABLE bot_escalation_intents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id          UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,

    intent_name     VARCHAR(100) NOT NULL,        -- e.g., "interested", "billing_inquiry"
    description     VARCHAR(500),                  -- admin note: "Caller shows buying interest"

    threshold       DECIMAL(4,3) NOT NULL DEFAULT 0.90,  -- 0.000 to 1.000 confidence threshold
    action          VARCHAR(30) NOT NULL DEFAULT 'TRANSFER_QUEUE',
                    -- TRANSFER_QUEUE, TRANSFER_AGENT, TRANSFER_EXTERNAL, HANGUP
    target          VARCHAR(100),                  -- queue name, agent extension, or phone number

    priority        INTEGER DEFAULT 0,             -- higher = check first
    enabled         BOOLEAN DEFAULT true,

    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_bot_escalation_bot ON bot_escalation_intents (bot_id) WHERE enabled = true;
CREATE INDEX idx_bot_escalation_tenant ON bot_escalation_intents (tenant_id);

-- Unique: one threshold per intent per bot
CREATE UNIQUE INDEX idx_bot_escalation_unique ON bot_escalation_intents (bot_id, intent_name)
    WHERE enabled = true;


-- ════════════════════════════════════════════════════════════
-- BOT KNOWLEDGE DOCUMENTS (RAG)
--
-- Admin uploads documents that the bot can reference during conversation.
-- MVP: Stuffed into LLM context window (no vector DB).
-- Future: Embeddings → ChromaDB/Pinecone vector search.
--
-- Content types:
--   FAQ       — Q&A pairs, bot answers directly
--   PRODUCT   — product info, pricing, features
--   SCRIPT    — call script (outbound dialer)
--   POLICY    — company policies, return rules, SLAs
--   CUSTOM    — anything else
-- ════════════════════════════════════════════════════════════

CREATE TABLE bot_knowledge_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id          UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,

    title           VARCHAR(200) NOT NULL,
    content         TEXT NOT NULL,                  -- the actual document text
    content_type    VARCHAR(30) NOT NULL DEFAULT 'FAQ',
                    -- FAQ, PRODUCT, SCRIPT, POLICY, CUSTOM
    language        VARCHAR(10) DEFAULT 'en',

    -- Future: vector embedding fields
    embedding_status VARCHAR(20) DEFAULT 'NONE',   -- NONE, PENDING, INDEXED, FAILED
    chunk_count      INTEGER DEFAULT 0,

    metadata        JSONB DEFAULT '{}',            -- extra structured data
    enabled         BOOLEAN DEFAULT true,
    display_order   INTEGER DEFAULT 0,

    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_bot_knowledge_bot ON bot_knowledge_documents (bot_id) WHERE enabled = true;
CREATE INDEX idx_bot_knowledge_tenant ON bot_knowledge_documents (tenant_id);
CREATE INDEX idx_bot_knowledge_type ON bot_knowledge_documents (bot_id, content_type) WHERE enabled = true;