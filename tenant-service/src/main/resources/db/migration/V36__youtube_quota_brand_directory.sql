-- 1) YouTube API quota (CREATOR_SHOWCASE.md rule 41). The daily quota belongs to OUR Google Cloud
--    project, shared by every creator's connected channel; an upload costs ~1,600 of 10,000 units.
--    Days follow YouTube's reset: midnight Pacific time.
CREATE TABLE youtube_quota_usage (
    quota_day   DATE        PRIMARY KEY,
    units_used  INT         NOT NULL DEFAULT 0,
    uploads     INT         NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Ops override of the configured budget (e.g. after Google raises the quota). One row at most.
CREATE TABLE youtube_quota_setting (
    id            SMALLINT    PRIMARY KEY DEFAULT 1,
    daily_limit   INT         NOT NULL,
    upload_units  INT         NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT youtube_quota_setting_single_ck CHECK (id = 1)
);

-- 2) Shared brand directory from an audience provider (rule 42; AUDIENCE_PROVIDER.md §10-§14).
--    Platform-level: one search or one company lookup serves every creator after it. Provider
--    results only become a creator's leads when the creator adds them to an audience.
CREATE TABLE lead_company (
    id                   UUID          PRIMARY KEY,
    domain               VARCHAR(255)  NOT NULL,
    name                 VARCHAR(255),
    industry             VARCHAR(32),
    country_code         VARCHAR(2),
    emails_available     INT,
    provider             VARCHAR(16)   NOT NULL,
    contacts_fetched_at  TIMESTAMPTZ,
    first_seen_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_seen_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_company_domain_uk UNIQUE (domain)
);

CREATE INDEX ix_lead_company_industry ON lead_company (industry, country_code);

CREATE TABLE lead_company_contact (
    company_id        UUID          NOT NULL,
    contact_point_id  UUID          NOT NULL,
    full_name         VARCHAR(160),
    position          VARCHAR(160),
    confidence        INT,
    provider_status   VARCHAR(24),
    first_seen_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_company_contact_pk PRIMARY KEY (company_id, contact_point_id),
    CONSTRAINT lead_company_contact_company_fk FOREIGN KEY (company_id) REFERENCES lead_company (id) ON DELETE CASCADE,
    CONSTRAINT lead_company_contact_point_fk FOREIGN KEY (contact_point_id) REFERENCES lead_contact_point (id)
);

-- A provider search, cached so the same search never calls the provider twice within the TTL.
CREATE TABLE lead_provider_search (
    query_key     CHAR(64)     PRIMARY KEY,
    provider      VARCHAR(16)  NOT NULL,
    description   VARCHAR(300) NOT NULL,
    result_count  INT          NOT NULL,
    searched_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE lead_provider_search_result (
    query_key   CHAR(64) NOT NULL,
    company_id  UUID     NOT NULL,
    rank        INT      NOT NULL,

    CONSTRAINT lead_provider_search_result_pk PRIMARY KEY (query_key, company_id),
    CONSTRAINT lead_provider_search_result_search_fk FOREIGN KEY (query_key) REFERENCES lead_provider_search (query_key) ON DELETE CASCADE,
    CONSTRAINT lead_provider_search_result_company_fk FOREIGN KEY (company_id) REFERENCES lead_company (id) ON DELETE CASCADE
);

-- Provider credits used per calendar month (free plans have a monthly allowance).
CREATE TABLE lead_provider_usage (
    usage_month   CHAR(7)      NOT NULL,
    provider      VARCHAR(16)  NOT NULL,
    credits_used  INT          NOT NULL DEFAULT 0,
    calls         INT          NOT NULL DEFAULT 0,

    CONSTRAINT lead_provider_usage_pk PRIMARY KEY (usage_month, provider)
);
