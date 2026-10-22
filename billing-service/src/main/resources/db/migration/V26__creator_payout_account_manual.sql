-- Creators are paid by hand from the ops dashboard until RazorpayX is available, so ops needs the
-- full account number to send the transfer. The RazorpayX contact and fund account are created
-- lazily, at a payout's first automatic send, so they are empty until then.
ALTER TABLE creator_payout_account
    ALTER COLUMN contact_id DROP NOT NULL,
    ALTER COLUMN fund_account_id DROP NOT NULL,
    ADD COLUMN account_number VARCHAR(18);
