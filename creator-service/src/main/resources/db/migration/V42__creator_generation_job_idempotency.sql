create unique index if not exists ux_creator_generation_jobs_active_idempotency
on creator_generation_jobs (
    tenant_id,
    user_id,
    job_type,
    (input_payload ->> 'idempotencyKey')
)
where input_payload ? 'idempotencyKey'
  and status in ('PENDING', 'RUNNING');
