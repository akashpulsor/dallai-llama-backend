-- Adds country and priority columns to sip_trunks for country-aware platform
-- trunk selection. Required by SipTrunkRepository.findBestPlatformTrunkForCountry().
--
-- country: ISO 3166-1 alpha-2 country code. Required for platform trunks
--          (tenant_id IS NULL); routing breaks if NULL on a platform row.
-- priority: lower value = higher priority. Default 100. Used to break ties
--           when multiple platform trunks match a country.

ALTER TABLE sip_trunks
    ADD COLUMN country VARCHAR(2),
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 100;

CREATE INDEX idx_sip_trunks_platform_country
    ON sip_trunks (country, status, priority, is_healthy)
    WHERE tenant_id IS NULL;