-- Phase 1 addendum: attach a login password to each virtual creator email identity so the
-- creator can be handed real credentials for whatever SMTP/mail service we plug in later
-- (Postmark app-password, SES SMTP, self-hosted mailu account, etc.). The password is
-- generated once at mint time from a secure random and stored as-is here so the human
-- operator can hand it to the creator.
--
-- This is DELIBERATELY plaintext storage today: no outbound mail provider is wired yet, so
-- there is nothing to hash-against, and the "hand it to the creator" flow needs the plain
-- value once. Rotation + encrypt-at-rest lands with the outbound provider integration; the
-- column is scoped narrow (32 chars) and access is gated behind /api/v1/internal/admin/*.

ALTER TABLE creator_email_identity
    ADD COLUMN email_password VARCHAR(64);

COMMENT ON COLUMN creator_email_identity.email_password IS
    'Provisional plaintext login password minted with the identity. Handed to the creator ONCE '
    'via /api/v1/internal/admin/lead-management/email/{tenantId}. To be replaced with '
    'encrypted-at-rest storage when an outbound-mail provider is chosen and rotation is wired.';
