-- People from Apollo.io (CREATOR_SHOWCASE.md rule 44): a shared, platform-level directory of brand
-- decision makers. Search is free and cached; an email is revealed (1 credit) once per person and
-- reused by every creator. When this month's credits are spent, people are still added to
-- audiences and the creator enters the email themselves.
CREATE TABLE lead_directory_person (
    id                  UUID          PRIMARY KEY,
    provider            VARCHAR(16)   NOT NULL,
    provider_person_id  VARCHAR(64)   NOT NULL,
    first_name          VARCHAR(80),
    -- Apollo hides most of the last name until the person is revealed ("S***h").
    last_name_hint      VARCHAR(80),
    full_name           VARCHAR(160),
    title               VARCHAR(160),
    company_name        VARCHAR(160),
    company_domain      VARCHAR(255),
    country_code        VARCHAR(2),
    has_email           BOOLEAN,
    contact_point_id    UUID,
    enriched_at         TIMESTAMPTZ,
    first_seen_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_seen_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_directory_person_uk UNIQUE (provider, provider_person_id),
    CONSTRAINT lead_directory_person_contact_fk FOREIGN KEY (contact_point_id) REFERENCES lead_contact_point (id)
);

CREATE TABLE lead_directory_person_result (
    query_key  CHAR(64) NOT NULL,
    person_id  UUID     NOT NULL,
    rank       INT      NOT NULL,

    CONSTRAINT lead_directory_person_result_pk PRIMARY KEY (query_key, person_id),
    CONSTRAINT lead_directory_person_result_search_fk FOREIGN KEY (query_key) REFERENCES lead_provider_search (query_key) ON DELETE CASCADE,
    CONSTRAINT lead_directory_person_result_person_fk FOREIGN KEY (person_id) REFERENCES lead_directory_person (id) ON DELETE CASCADE
);
