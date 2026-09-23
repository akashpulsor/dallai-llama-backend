-- Phase 1 of Lead Management: the per-creator email identity minted on subscription.activated.
--
-- These are application-managed VIRTUAL identities on partner.dalaillama.in -- one Cloudflare
-- catch-all MX handles them all at the DNS layer, so we do NOT provision a per-creator mailbox
-- with Hostinger (the root dalaillama.in mail continues to point at Hostinger, untouched).
--
-- The local_part is DETERMINISTIC from the tenant UUID (cr_<uuid-with-no-dashes>) so the same
-- creator always resolves to the same address; a display-name change never changes the email.
-- Enforced via UNIQUE on (tenant_id) and UNIQUE on (email) -- either constraint blocks a
-- duplicate re-provision.

CREATE TABLE creator_email_identity (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    local_part      VARCHAR(64) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(200),
    status          VARCHAR(24) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT creator_email_identity_tenant_uk UNIQUE (tenant_id),
    CONSTRAINT creator_email_identity_email_uk  UNIQUE (email),
    CONSTRAINT creator_email_identity_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE
);

COMMENT ON TABLE  creator_email_identity IS
    'Deterministic virtual email identity for a creator, minted on subscription.activated. '
    'Local part is cr_<tenantId-no-dashes>; domain is partner.dalaillama.in. Not a real mailbox '
    'at the provider -- the domain is served by a Cloudflare catch-all rule.';
COMMENT ON COLUMN creator_email_identity.local_part IS
    'cr_<32 lowercase hex chars from tenantId with hyphens removed>. Kept as a separate column '
    'from email so a future domain migration does not require recomputing the local part.';
COMMENT ON COLUMN creator_email_identity.status IS
    'Lifecycle state: PROVISIONED (active), DISABLED (suspended, still resolves), '
    'RETIRED (creator gone, kept for audit). Phase 1 only writes PROVISIONED.';
