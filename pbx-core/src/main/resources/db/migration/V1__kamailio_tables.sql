-- V1__kamailio_tables.sql
-- Standard Kamailio tables. PBX-Core manages them via JPA.
-- Kamailio connects to same DB with read access (+ write for location only).

CREATE TABLE IF NOT EXISTS version (
    id              SERIAL PRIMARY KEY,
    table_name      VARCHAR(32) NOT NULL UNIQUE,
    table_version   INT NOT NULL DEFAULT 0
);

INSERT INTO version (table_name, table_version) VALUES
    ('subscriber', 7), ('domain', 2), ('domain_attrs', 1),
    ('dispatcher', 4), ('dialplan', 2), ('location', 9), ('uacreg', 5)
ON CONFLICT (table_name) DO NOTHING;

-- SIP authentication (PBX-Core writes, Kamailio reads for auth)
CREATE TABLE subscriber (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL,
    domain          VARCHAR(64) NOT NULL,
    password        VARCHAR(64) DEFAULT '',
    ha1             VARCHAR(128) NOT NULL,
    ha1b            VARCHAR(128) NOT NULL,
    rpid            VARCHAR(255),
    -- PBX-Core extensions (Kamailio ignores these)
    tenant_id       UUID NOT NULL,
    subscription_id UUID NOT NULL,
    subscriber_type VARCHAR(20) DEFAULT 'AGENT',
    display_name    VARCHAR(100),
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(username, domain)
);

-- SIP domains
CREATE TABLE domain (
    id              SERIAL PRIMARY KEY,
    domain          VARCHAR(64) NOT NULL UNIQUE,
    did             VARCHAR(64),
    last_modified   TIMESTAMP DEFAULT NOW(),
    tenant_id       UUID NOT NULL,
    subscription_id UUID,
    is_active       BOOLEAN DEFAULT TRUE
);

CREATE TABLE domain_attrs (
    id              SERIAL PRIMARY KEY,
    did             VARCHAR(64),
    name            VARCHAR(32) NOT NULL,
    type            INT NOT NULL DEFAULT 0,
    value           VARCHAR(255) NOT NULL,
    last_modified   TIMESTAMP DEFAULT NOW()
);

-- FreeSWITCH load balancing sets
CREATE TABLE dispatcher (
    id              SERIAL PRIMARY KEY,
    setid           INT NOT NULL DEFAULT 0,
    destination     VARCHAR(192) NOT NULL,
    flags           INT DEFAULT 0,
    priority        INT DEFAULT 0,
    attrs           VARCHAR(128) DEFAULT '',
    description     VARCHAR(128) DEFAULT '',
    tenant_id       UUID,
    subscription_id UUID,
    UNIQUE(setid, destination)
);

-- Number manipulation
CREATE TABLE dialplan (
    id              SERIAL PRIMARY KEY,
    dpid            INT NOT NULL,
    pr              INT NOT NULL DEFAULT 0,
    match_op        INT NOT NULL DEFAULT 1,
    match_exp       VARCHAR(64) NOT NULL,
    match_len       INT NOT NULL DEFAULT 0,
    subst_exp       VARCHAR(64) DEFAULT '',
    repl_exp        VARCHAR(256) NOT NULL,
    attrs           VARCHAR(512) DEFAULT '',
    tenant_id       UUID NOT NULL,
    subscription_id UUID NOT NULL,
    product_code    VARCHAR(30),
    UNIQUE(dpid, pr, match_exp)
);

-- Kamailio manages this (registrations). PBX-Core does NOT write here.
CREATE TABLE location (
    id              SERIAL PRIMARY KEY,
    ruid            VARCHAR(64) NOT NULL DEFAULT '',
    username        VARCHAR(64) NOT NULL DEFAULT '',
    domain          VARCHAR(64) DEFAULT NULL,
    contact         VARCHAR(512) NOT NULL DEFAULT '',
    received        VARCHAR(128) DEFAULT NULL,
    path            VARCHAR(512) DEFAULT NULL,
    expires         TIMESTAMP NOT NULL DEFAULT '2030-05-28 21:32:15',
    q               REAL NOT NULL DEFAULT 1.0,
    callid          VARCHAR(255) NOT NULL DEFAULT 'Default-Call-ID',
    cseq            INT NOT NULL DEFAULT 1,
    last_modified   TIMESTAMP NOT NULL DEFAULT NOW(),
    flags           INT NOT NULL DEFAULT 0,
    cflags          INT NOT NULL DEFAULT 0,
    user_agent      VARCHAR(255) NOT NULL DEFAULT '',
    socket          VARCHAR(64) DEFAULT NULL,
    methods         INT DEFAULT NULL,
    instance        VARCHAR(255) DEFAULT NULL,
    reg_id          INT NOT NULL DEFAULT 0,
    server_id       INT NOT NULL DEFAULT 0,
    connection_id   INT NOT NULL DEFAULT -1,
    keepalive       INT NOT NULL DEFAULT 0,
    partition       INT NOT NULL DEFAULT 0
);

-- SIP trunk registrations (outbound)
CREATE TABLE uacreg (
    id              SERIAL PRIMARY KEY,
    l_uuid          VARCHAR(64) NOT NULL DEFAULT '' UNIQUE,
    l_username      VARCHAR(64) NOT NULL DEFAULT '',
    l_domain        VARCHAR(64) NOT NULL DEFAULT '',
    r_username      VARCHAR(64) NOT NULL DEFAULT '',
    r_domain        VARCHAR(128) NOT NULL DEFAULT '',
    realm           VARCHAR(64) NOT NULL DEFAULT '',
    auth_username   VARCHAR(64) NOT NULL DEFAULT '',
    auth_password   VARCHAR(64) NOT NULL DEFAULT '',
    auth_ha1        VARCHAR(128) NOT NULL DEFAULT '',
    auth_proxy      VARCHAR(128) NOT NULL DEFAULT '',
    expires         INT NOT NULL DEFAULT 0,
    flags           INT NOT NULL DEFAULT 0,
    reg_delay       INT NOT NULL DEFAULT 0,
    contact_addr    VARCHAR(255) NOT NULL DEFAULT '',
    socket          VARCHAR(128) NOT NULL DEFAULT '',
    tenant_id       UUID,
    trunk_id        UUID
);