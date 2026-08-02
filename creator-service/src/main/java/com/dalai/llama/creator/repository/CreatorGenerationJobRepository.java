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
              and job_type like 'SCREENPLAY_VIDEO_%'
              and input_payload ->> 'runId' = :runId
              and (status <> 'RUNNING' or progress > 5)
            order by completed_at desc nulls last, created_at desc
            limit 1
            """, nativeQuery = true)
    Optional<CreatorGenerationJob> findLatestScreenplayVideoRunJob(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("runId") String runId
    );

    @Query(value = """
            select *
              from creator_generation_jobs
             where tenant_id = :tenantId
               and user_id = :userId
               and job_type like 'SCREENPLAY_VIDEO_%'
               and input_payload ->> 'scriptId' = :scriptId
               and (status <> 'RUNNING' or progress > 5)
             order by completed_at desc nulls last, created_at desc
             limit 1
            """, nativeQuery = true)
    Optional<CreatorGenerationJob> findLatestScreenplayVideoRunJobForScript(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("scriptId") String scriptId
    );

    @Query(value = """
            select *
            from creator_generation_jobs
            where tenant_id = :tenantId
              and user_id = :userId
              and job_type = 'SCREENPLAY_VIDEO_AUDIO_PACK'
              and status = 'COMPLETED'
              and input_payload ->> 'runId' = :runId
            order by completed_at desc nulls last, created_at desc
            limit 1
            """, nativeQuery = true)
    Optional<CreatorGenerationJob> findLatestScreenplayVideoAudioRunJob(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("runId") String runId
    );

    @Query(value = """
            select *
            from creator_generation_jobs
            where tenant_id = :tenantId
              and user_id = :userId
              and job_type = :jobType
              and input_payload ->> 'idempotencyKey' = :idempotencyKey
              and status in ('PENDING', 'RUNNING', 'PAUSED')
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

    @Query(value = "select pg_try_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
    boolean tryAcquireTransactionalAdvisoryLock(@Param("lockKey") String lockKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update creator_generation_jobs
               set status = 'RUNNING',
                   progress = greatest(progress, 5),
                   started_at = coalesce(started_at, now()),
                   output_payload = coalesce(output_payload, '{}'::jsonb)
                       || jsonb_build_object(
                            'executionClaimedAt', now()::text,
                            'executionClaimedBy', :claimedBy,
                            'lastHeartbeatAt', now()::text
                          )
             where id = :jobId
               and status in ('PENDING', 'RUNNING')
               and not jsonb_exists(coalesce(output_payload, '{}'::jsonb), 'executionClaimedAt')
            """, nativeQuery = true)
    int claimExecution(
            @Param("jobId") UUID jobId,
            @Param("claimedBy") String claimedBy
    );

    @Query(value = """
            select *
              from creator_generation_jobs
             where job_type in ('SHORTS_GENERATE', 'SHORTS_CANDIDATE_RERENDER', 'SHORTS_VISUAL_ANALYSIS')
               and status = 'RUNNING'
               and completed_at is null
               and jsonb_exists(coalesce(output_payload, '{}'::jsonb), 'executionClaimedAt')
               and coalesce(
                    nullif(output_payload ->> 'lastHeartbeatAt', '')::timestamptz,
                    nullif(output_payload ->> 'executionClaimedAt', '')::timestamptz,
                    started_at,
                    created_at
               ) < :staleBefore
             order by coalesce(started_at, created_at) asc
             limit :limit
            """, nativeQuery = true)
    List<CreatorGenerationJob> findStaleClaimedShortGenerationJobs(
            @Param("staleBefore") java.time.OffsetDateTime staleBefore,
            @Param("limit") int limit
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update creator_generation_jobs
               set status = 'PENDING',
                   progress = least(progress, 8),
                   started_at = null,
                   output_payload = (
                        coalesce(output_payload, '{}'::jsonb)
                        - 'executionClaimedAt'
                        - 'executionClaimedBy'
                        - 'lastHeartbeatAt'
                   ) || jsonb_build_object(
                        'message', :message,
                        'recoveryQueuedAt', now()::text,
                        'recoveryReason', :reason,
                        'recoveryAttemptCount', coalesce(nullif(output_payload ->> 'recoveryAttemptCount', '')::int, 0) + 1
                   )
             where id = :jobId
               and job_type in ('SHORTS_GENERATE', 'SHORTS_CANDIDATE_RERENDER', 'SHORTS_VISUAL_ANALYSIS')
               and status = 'RUNNING'
               and completed_at is null
               and jsonb_exists(coalesce(output_payload, '{}'::jsonb), 'executionClaimedAt')
               and coalesce(
                    nullif(output_payload ->> 'lastHeartbeatAt', '')::timestamptz,
                    nullif(output_payload ->> 'executionClaimedAt', '')::timestamptz,
                    started_at,
                    created_at
               ) < :staleBefore
            """, nativeQuery = true)
    int clearStaleShortGenerationClaimForRetry(
            @Param("jobId") UUID jobId,
            @Param("staleBefore") java.time.OffsetDateTime staleBefore,
            @Param("reason") String reason,
            @Param("message") String message
    );
}
