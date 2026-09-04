-- Distinguishes what a client_review_payment row is actually for: 'LOCK' (the initial pay-to-
-- approve-and-lock charge) vs 'EXTRA_REVIEW' (a paid extra review round on an already-locked
-- project). Both flows share this one table and both resolve through the same Razorpay
-- order/verify plumbing, so nothing before this needed to tell them apart -- but the webhook-
-- driven safety net being added alongside this (RazorpayWebhookOrchestrationService now handles
-- client-review payments too, not just wallet/funding ones) has to know which action to trigger
-- on pre-production-service (lock the project vs open a paid review) without any client request
-- in flight to ask. Existing rows backfill to 'LOCK' -- approximate for any historical
-- EXTRA_REVIEW row, but harmless: this column is only read by the new webhook path, which never
-- looks at rows older than itself.
ALTER TABLE client_review_payment ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'LOCK';
ALTER TABLE client_review_payment ALTER COLUMN kind DROP DEFAULT;
