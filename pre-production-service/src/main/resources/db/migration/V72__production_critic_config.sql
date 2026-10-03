-- Whether production's critics run, set from the ops page.
--
-- Script, camera plan, lighting plan and the per-shot pre-flight each run a critic that can send
-- the plan back for another attempt; on one project that was 666 critic calls driving 806 plan
-- generations. Ops can switch them off to see what production costs and produces without them.
-- One row, id = 1; no row means on, the behaviour every project has had until now.
CREATE TABLE production_critic_config (
    id          SMALLINT    PRIMARY KEY CHECK (id = 1),
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
