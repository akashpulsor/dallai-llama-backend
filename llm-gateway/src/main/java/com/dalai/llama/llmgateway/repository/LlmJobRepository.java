package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dalai.llama.llmgateway.domain.JobStatus;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmJobRepository extends JpaRepository<LlmJob, UUID> {

    Optional<LlmJob> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    /** Feeds the admin ops dashboard's "stuck jobs" view. Two flavours in one call:
     *  <ol>
     *    <li>PROCESSING with a stale {@code processingStartedAt} -- a worker crashed mid-call.</li>
     *    <li>PENDING with a stale {@code createdAt} -- Kafka consumer never picked it up.</li>
     *    <li>Any FAILED job newer than the cutoff -- so an operator can retry recent failures
     *        without an old FAILED backlog dominating the view.</li>
     *  </ol>
     *  The cutoff is passed in, not hardcoded, so the same query serves "look at the last hour"
     *  and "look at the last week" without a second method.
     */
    List<LlmJob> findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Collection<JobStatus> statuses, OffsetDateTime cutoff);

    /** Raw per-model-type cost rollup for one project -- the source data the pricing layer (in
     * billing-service) reads to build a quote. Joins model_master for the {@code type} (llm-gateway's
     * own vocabulary; no pricing categories here). Only COMPLETED jobs with a persisted cost count. */
    @Query(value = """
            SELECT mm.type AS modelType, COALESCE(SUM(j.cost), 0) AS cost
            FROM llm_job j
            JOIN model_master mm ON mm.model_id = j.model_id
            WHERE j.tenant_id = :tenantId AND j.project_id = :projectId
              AND j.status = 'COMPLETED' AND j.cost IS NOT NULL
            GROUP BY mm.type
            """, nativeQuery = true)
    List<ProjectJobCostRow> sumCostByTypeForProject(@Param("tenantId") String tenantId, @Param("projectId") UUID projectId);

    /** Native-query projection: one row per model_master.type with its summed cost. */
    interface ProjectJobCostRow {
        String getModelType();
        java.math.BigDecimal getCost();
    }
}
