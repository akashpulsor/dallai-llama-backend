-- Paying a creator their share of a project in real money, through RazorpayX Payouts.
--
-- creator_payout_account: where a creator wants to be paid -- one per tenant. The bank account
-- number itself is handed to RazorpayX once (as a fund account) and never stored here; only its
-- last four digits are kept, for the creator's own "paid to ••1234" line.
CREATE TABLE creator_payout_account (
    tenant_id        UUID         PRIMARY KEY,
    method           VARCHAR(16)  NOT NULL CHECK (method IN ('BANK_ACCOUNT', 'UPI')),
    holder_name      VARCHAR(120) NOT NULL,
    ifsc             VARCHAR(11),
    account_last4    VARCHAR(4),
    vpa              VARCHAR(100),
    contact_id       VARCHAR(64)  NOT NULL,
    fund_account_id  VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- creator_payout: one per project, opened when the client's final (lock) payment is captured. The
-- amount is what the client paid that reached the creator's wallet, minus the project's AI usage
-- (provider cost + the platform's margin on it) and the gateway's fees on the client's payments.
-- It is worked out when the payout is prepared, not when it is opened, so usage billed in between
-- still counts; the breakdown is kept so the creator can see how the figure was reached.
CREATE TABLE creator_payout (
    id                 UUID           PRIMARY KEY,
    tenant_id          UUID           NOT NULL,
    project_id         UUID           NOT NULL UNIQUE,
    status             VARCHAR(20)    NOT NULL,
    client_paid        NUMERIC(15, 2),
    production_charged NUMERIC(15, 2),
    gateway_fees       NUMERIC(15, 2),
    amount             NUMERIC(15, 2),
    currency           VARCHAR(8)     NOT NULL,
    -- Bumped each time the amount is taken from the wallet; part of every idempotency key, so a
    -- retry after a failure is a new debit and a new RazorpayX payout, not a replay of the old one.
    attempts           INT            NOT NULL DEFAULT 0,
    fund_account_id    VARCHAR(64),
    gateway_payout_id  VARCHAR(64),
    utr                VARCHAR(64),
    failure_reason     VARCHAR(500),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    paid_at            TIMESTAMPTZ
);

CREATE INDEX idx_creator_payout_tenant ON creator_payout (tenant_id, created_at DESC);
CREATE INDEX idx_creator_payout_status ON creator_payout (status);
CREATE UNIQUE INDEX uq_creator_payout_gateway_id ON creator_payout (gateway_payout_id) WHERE gateway_payout_id IS NOT NULL;
