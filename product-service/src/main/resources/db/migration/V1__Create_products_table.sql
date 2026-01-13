CREATE TABLE products (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    type VARCHAR(30) NOT NULL, -- AI_CONTACT_CENTER, CONV_IVR, BASIC_PBX, OUTBOUND_DIALER, VIRTUAL_RECEPTIONIST
    active BOOLEAN DEFAULT true,
    features JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

-- Seed default products
INSERT INTO products (id, code, name, description, type, features) VALUES
    (gen_random_uuid(), 'AI_CC', 'AI Contact Center', 'Full-featured AI-powered contact center', 'AI_CONTACT_CENTER',
     '{"inbound": true, "outbound": true, "aiStt": true, "aiLlm": true, "sentiment": true, "queues": true, "recording": true, "supervisor": true}'),
    (gen_random_uuid(), 'CONV_IVR', 'Conversational IVR', 'AI-powered interactive voice response', 'CONVERSATIONAL_IVR',
     '{"nlp": true, "llm": true, "multiTurn": true, "handoff": true, "multiLanguage": true, "flowBuilder": true}'),
    (gen_random_uuid(), 'BASIC_PBX', 'Basic PBX', 'Traditional PBX functionality', 'BASIC_PBX',
     '{"inbound": true, "outbound": true, "transfer": true, "voicemail": true, "ringGroups": true, "basicIvr": true}'),
    (gen_random_uuid(), 'OUTBOUND_DIALER', 'Outbound Dialer', 'Campaign-based outbound calling', 'OUTBOUND_DIALER',
     '{"predictive": true, "progressive": true, "preview": true, "campaigns": true, "dncManagement": true}'),
    (gen_random_uuid(), 'VIRTUAL_RECEPTIONIST', 'Virtual Receptionist', 'AI-powered front desk', 'VIRTUAL_RECEPTIONIST',
     '{"24x7": true, "appointmentScheduling": true, "faq": true, "messageTaking": true, "multiLanguage": true}');