package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorGenerationJobRepository extends JpaRepository<CreatorGenerationJob, UUID> {

    Optional<CreatorGenerationJob> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    List<CreatorGenerationJob> findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId);

    @Query(value = """
            select *
            from creator_generation_jobs
            where tenant_id = :tenantId
              and user_id = :userId
              and job_type = :jobType
              and input_payload ->> 'idempotencyKey' = :idempotencyKey
              and status in ('PENDING', 'RUNNING')
            order by created_at desc
            limit 1
            """, nativeQuery = true)
    Optional<CreatorGenerationJob> findActiveByIdempotencyKey(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("jobType") String jobType,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query(value = """
            select *
            from creator_generation_jobs
            where tenant_id = :tenantId
              and user_id = :userId
              and job_type = :jobType
              and input_payload ->> 'idempotencyKey' = :idempotencyKey
              and status = 'COMPLETED'
            order by completed_at desc nulls last, created_at desc
            limit 1
            """, nativeQuery = true)
    Optional<CreatorGenerationJob> findLatestCompletedByIdempotencyKey(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("jobType") String jobType,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update creator_generation_jobs
               set status = 'RUNNING',
                   progress = greatest(progress, 5),
                   started_at = coalesce(started_at, now()),
                   output_payload = coalesce(output_payload, '{}'::jsonb)
                       || jsonb_build_object(
                            'executionClaimedAt', now()::text,
                            'executionClaimedBy', :claimedBy
                          )
             where id = :jobId
               and status in ('PENDING', 'RUNNING')
               and not jsonb_exists(coalesce(output_payload, '{}'::jsonb), 'executionClaimedAt')
            """, nativeQuery = true)
    int claimExecution(
            @Param("jobId") UUID jobId,
            @Param("claimedBy") String claimedBy
    );
}
