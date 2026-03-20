-- V12__cdr_url.sql


ALTER TABLE call_records
ADD COLUMN IF NOT EXISTS transcript_url VARCHAR(500);