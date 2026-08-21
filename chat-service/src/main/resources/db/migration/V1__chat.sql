CREATE TABLE IF NOT EXISTS chat_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id UUID,
    title VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_session_tenant ON chat_session (tenant_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    action_type VARCHAR(32),
    action_status VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message (session_id, created_at ASC);

CREATE TABLE IF NOT EXISTS embedded_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    scope_id UUID,
    source_service VARCHAR(64) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    embedding DOUBLE PRECISION[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_embedded_document_source UNIQUE (tenant_id, source_service, source_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_embedded_document_tenant ON embedded_document (tenant_id);
CREATE INDEX IF NOT EXISTS idx_embedded_document_scope ON embedded_document (tenant_id, scope_id);
