-- 1. Add AI Transparency and Privacy fields
ALTER TABLE compliance_policies
    ADD COLUMN IF NOT EXISTS ai_disclosure_required BOOLEAN DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS anonymize_transcript BOOLEAN DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS redaction_level VARCHAR(50) DEFAULT 'PARTIAL';

-- 2. Handle the Retention Split
-- We rename the old 'retention_days' to 'audio_retention_days' and add the transcript one
ALTER TABLE compliance_policies
    RENAME COLUMN retention_days TO audio_retention_days;

ALTER TABLE compliance_policies
    ADD COLUMN IF NOT EXISTS transcript_retention_days INTEGER DEFAULT 365;

-- 3. Add Timezone support (Critical for AI outbound logic)
ALTER TABLE compliance_policies
    ADD COLUMN IF NOT EXISTS time_zone_id VARCHAR(100) DEFAULT 'Asia/Kolkata';

-- 4. Optimize 'blocked_days' for JSON operations
-- If you previously used a simple VARCHAR, converting to JSONB allows for faster querying
ALTER TABLE compliance_policies
    ALTER COLUMN blocked_days TYPE JSONB USING blocked_days::JSONB;