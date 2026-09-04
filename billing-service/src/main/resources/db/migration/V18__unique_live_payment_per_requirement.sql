-- Confirmed live: with multiple billing-service pods, an application-level "does a SUCCESS
-- payment already exist" check (PaymentServiceImpl.createOrder) is a read-then-write race with
-- no shared lock across JVMs -- two concurrent requests for the same project_requirement_id,
-- landing on two different pods, can both read "no payment yet" before either has written
-- anything. Postgres is the only thing every pod actually shares, so the guarantee has to live
-- here: at most one *live* (not yet failed/cancelled/refunded) payment per requirement, enforced
-- atomically regardless of pod count. FAILED/CANCELLED/REFUNDED stay unconstrained so a
-- genuinely failed payment can still be retried with a fresh order.
--
-- Cannot apply this over the table as-is: a cluster-wide audit found 2 requirements that ALREADY
-- violate it -- 1c6c4342-c885-44a1-93da-e302ccb35150 (2 SUCCESS payments, 51s apart) and
-- 4075149f-e24b-42fe-82f4-9661abfc1f66 (5 SUCCESS payments over ~80 minutes) -- both real charges
-- from this exact bug, before this fix existed. These are financial records, not bad data: they
-- represent real captured payments and must never be deleted or rewritten by a migration. The
-- excess ones (all but the earliest per requirement) are excluded from this index by id so the
-- migration can run without touching them; they still need a manual refund/reconciliation
-- decision on the business side -- this migration deliberately does not make that decision.
CREATE UNIQUE INDEX uq_payment_live_per_requirement
    ON payments(project_requirement_id)
    WHERE project_requirement_id IS NOT NULL
      AND status IN ('PENDING', 'PROCESSING', 'SUCCESS')
      AND id NOT IN (
          '5347d035-65e4-4307-a21d-878c17ced03d',  -- excess duplicate, requirement 1c6c4342-...
          '8e469d0a-9a06-4e7c-830c-3cfe225c83f1',  -- excess duplicate, requirement 4075149f-...
          '460f28df-ff05-4192-b631-23318d47deb9',
          '8b202d89-0e20-4e5c-8060-35a4d943f793',
          '8881bf80-123c-401d-a90c-d13fdcb52701'
      );
