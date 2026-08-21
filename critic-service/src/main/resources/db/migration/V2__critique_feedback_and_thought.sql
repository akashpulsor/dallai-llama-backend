CREATE TABLE IF NOT EXISTS critique_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES critique_session(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    approved BOOLEAN NOT NULL,
    edit_locations TEXT,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_critique_feedback_session ON critique_feedback (session_id);
CREATE INDEX IF NOT EXISTS idx_critique_feedback_tenant ON critique_feedback (tenant_id, created_at DESC);

-- No FK to critique_session: thought rows are written throughout critique() *before* the session
-- row itself is persisted (the session id is generated up front and used to correlate log lines
-- as each step completes), so a strict FK would reject the very first log line.
CREATE TABLE IF NOT EXISTS critique_thought (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    step VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_critique_thought_session ON critique_thought (session_id, created_at ASC);
