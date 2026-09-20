package com.dalai.llama.observability;

/**
 * SLF4J MDC key constants shared across every backend service. Kept as a single class so a Loki
 * query can name any of these without checking which service emitted the line: they mean the
 * same thing everywhere.
 *
 * <p>Snake_case, not camelCase: the JSON log encoder emits MDC keys verbatim and Loki's LogQL
 * label matcher syntax reads {@code tenant_id="..."} more naturally than {@code tenantId="..."}.
 * This is a wire contract with the Loki dashboards; do not rename without updating the
 * dashboards.
 */
public final class MdcKeys {

    public static final String TENANT_ID = "tenant_id";
    public static final String PROJECT_ID = "project_id";
    public static final String SHOT_ID = "shot_id";
    public static final String JOB_ID = "job_id";
    public static final String USER_ID = "user_id";
    public static final String REQUEST_ID = "request_id";

    private MdcKeys() {
    }
}
