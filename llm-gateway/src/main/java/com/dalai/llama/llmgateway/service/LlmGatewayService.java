package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.JobStatus;
import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import com.dalai.llama.llmgateway.domain.entity.RateCard;
import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.dto.EstimateResponse;
import com.dalai.llama.llmgateway.dto.JobStatusResponse;
import com.dalai.llama.llmgateway.dto.UsageDto;
import com.dalai.llama.llmgateway.kafka.BillingEvent;
import com.dalai.llama.llmgateway.kafka.BillingEventPublisher;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import com.dalai.llama.llmgateway.service.provider.CanonicalRequest;
import com.dalai.llama.llmgateway.service.provider.LlmProvider;
import com.dalai.llama.llmgateway.service.provider.LlmProviderException;
import com.dalai.llama.llmgateway.service.provider.LlmResponse;
import com.dalai.llama.llmgateway.service.provider.ProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates doc §2's sync path: {@code POST /v1/chat} holds the connection, checks the
 * Redis-backed rate limiter directly, and dispatches to the provider inline -- idempotency,
 * rate limiting, and audit logging all still apply, only the Kafka async transport is skipped
 * (that path -- {@code /v1/chat/async} -- is not built in this v1 slice).
 *
 * <p><b>Crash safety / retry state machine</b> (added per explicit follow-up request, beyond
 * doc §5's baseline): a job's row is durably persisted as {@link JobStatus#PROCESSING}
 * (via {@link JobPersistenceService}) <i>before</i> the provider is called, and dispatch is
 * registered in {@link InFlightJobRegistry} so a live call can be cancelled. If this pod crashes
 * mid-call, the row survives showing PROCESSING; the next request against the same idempotency
 * key ({@link #isStale}) recognises it as stranded once past {@code model.timeoutMs *
 * STALENESS_MULTIPLIER} and self-heals it to {@link JobStatus#TIMED_OUT} before redispatching on
 * the SAME job row (so the audit trail stays one job_id, not a new one per retry). A genuinely
 * still-in-flight duplicate (not stale) is returned as-is, per doc §5, never redispatched.
 */
@Slf4j
@Service
public class LlmGatewayService {

    /** Multiplier applied to model.timeoutMs before a PROCESSING row is presumed crashed rather
     * than genuinely slow -- generous on purpose; false-positive early retries would double-bill. */
    private static final int STALENESS_MULTIPLIER = 2;

    private final IdempotencyService idempotencyService;
    private final ModelRouterService modelRouterService;
    private final RateLimiterService rateLimiterService;
    private final ProviderRegistry providerRegistry;
    private final LlmJobRepository llmJobRepository;
    private final JobPersistenceService jobPersistenceService;
    private final BillingEventPublisher billingEventPublisher;
    private final InFlightJobRegistry inFlightJobRegistry;
    private final BillingWalletClient billingWalletClient;
    private final boolean walletGuardEnabled;
    private final BigDecimal minimumWalletBalance;
    private final PromptTemplateService promptTemplateService;
    private final ProviderConcurrencyLeaseService concurrencyLeaseService;
    private final ObjectMapper objectMapper;

    public LlmGatewayService(
            IdempotencyService idempotencyService,
            ModelRouterService modelRouterService,
            RateLimiterService rateLimiterService,
            ProviderRegistry providerRegistry,
            LlmJobRepository llmJobRepository,
            JobPersistenceService jobPersistenceService,
            BillingEventPublisher billingEventPublisher,
            InFlightJobRegistry inFlightJobRegistry,
            BillingWalletClient billingWalletClient,
            @Value("${llm-gateway.billing.wallet-guard-enabled}") boolean walletGuardEnabled,
            @Value("${llm-gateway.billing.minimum-wallet-balance}") BigDecimal minimumWalletBalance,
            PromptTemplateService promptTemplateService,
            ProviderConcurrencyLeaseService concurrencyLeaseService,
            ObjectMapper objectMapper
    ) {
        this.idempotencyService = idempotencyService;
        this.modelRouterService = modelRouterService;
        this.rateLimiterService = rateLimiterService;
        this.providerRegistry = providerRegistry;
        this.llmJobRepository = llmJobRepository;
        this.jobPersistenceService = jobPersistenceService;
        this.billingEventPublisher = billingEventPublisher;
        this.inFlightJobRegistry = inFlightJobRegistry;
        this.billingWalletClient = billingWalletClient;
        this.walletGuardEnabled = walletGuardEnabled;
        this.minimumWalletBalance = minimumWalletBalance;
        this.promptTemplateService = promptTemplateService;
        this.concurrencyLeaseService = concurrencyLeaseService;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(String tenantId, String idempotencyKey, ChatRequest request) {
        if (tenantId == null || tenantId.isBlank()) {
            throw GatewayException.badRequest("X-Tenant-ID header is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw GatewayException.badRequest("Idempotency-Key header is required");
        }

        Optional<LlmJob> existingOpt = idempotencyService.findExisting(tenantId, idempotencyKey);
        if (existingOpt.isPresent()) {
            return handleExisting(existingOpt.get(), tenantId, request);
        }

        // Fail fast on a bad model_id/entitlement, or an unaffordable tenant, before ever
        // claiming a row -- a rejected request should never leave a PROCESSING row behind.
        modelRouterService.route(tenantId, request.modelId());
        requireSufficientBalance(tenantId, request.projectId());
        Optional<LlmJob> claimed = jobPersistenceService.claimNewJob(tenantId, idempotencyKey, request.modelId(), request.projectId());
        if (claimed.isPresent()) {
            return dispatch(claimed.get(), tenantId, request, 1);
        }

        // Lost the race to a concurrent identical request between the check above and the
        // insert -- the unique (tenant_id, idempotency_key) index caught it. Treat it exactly
        // like the "existing job found" path.
        LlmJob raced = idempotencyService.findExisting(tenantId, idempotencyKey)
                .orElseThrow(() -> GatewayException.badRequest("Unable to claim or find job for idempotency_key=" + idempotencyKey));
        return handleExisting(raced, tenantId, request);
    }

    private ChatResponse handleExisting(LlmJob existing, String tenantId, ChatRequest request) {
        if (existing.getStatus() == JobStatus.COMPLETED) {
            // Recovers the actual result for a caller that never received the original response
            // (e.g. its own pod crashed mid-request) -- usage/cost are intentionally omitted, not
            // recomputed: this is a replay of an already-billed job, not a fresh billing event.
            log.info("Idempotent replay, already completed jobId={}", existing.getJobId());
            return new ChatResponse(existing.getJobId(), existing.getModelId(), existing.getResultContent(), null, 0);
        }
        if (existing.getStatus() == JobStatus.PROCESSING && !isStale(existing)) {
            // Doc §5: genuinely still in flight (this pod, or -- once replicaCount > 1 --
            // another) -- never redispatch, just hand back the existing job_id.
            log.info("Idempotent replay, still processing jobId={}", existing.getJobId());
            return new ChatResponse(existing.getJobId(), existing.getModelId(), null, null, 0);
        }
        if (existing.getStatus() == JobStatus.PROCESSING) {
            // Stale: presumed crashed. Self-heal to a terminal state, then retry on the SAME row.
            jobPersistenceService.finalizeIfStillProcessing(existing.getJobId(), JobStatus.TIMED_OUT,
                    "Stranded PROCESSING row exceeded staleness threshold, presumed crashed");
        }
        // Re-check affordability before every retry too -- balance may have changed since the
        // original (now-terminal) attempt, and the existing row stays untouched if this rejects.
        requireSufficientBalance(tenantId, existing.getProjectId());
        LlmJob job = jobPersistenceService.markProcessingForRetry(existing.getJobId(), existing.getAttemptCount() + 1);
        return dispatch(job, tenantId, request, job.getAttemptCount());
    }

    /** Doc: caller's explicit ask -- a tenant below their configured minimum wallet balance is
     * rejected with 402 before any job row is claimed/reset, so an unaffordable request never
     * costs another tenant's rate-limit budget or leaves gateway-side state behind. A tenant_id
     * that isn't a valid UUID can't be checked against billing-service's UUID-keyed wallet API,
     * so it's rejected the same way creator-service's BillingWalletGuardInterceptor does.
     * <p>
     * When {@code projectId} is present, the same synchronous call also carries the per-project
     * spend cap (real accumulated cost vs. the project's quoted price, evaluated in billing-
     * service) -- a distinct 402 message so a caller/UI can tell "recharge your wallet" apart from
     * "this project has hit its budget," which need different next steps. */
    private void requireSufficientBalance(String tenantId, UUID projectId) {
        if (!walletGuardEnabled) {
            return;
        }
        UUID tenantUuid;
        try {
            tenantUuid = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            throw GatewayException.badRequest("X-Tenant-ID must be a valid UUID for billing wallet checks");
        }
        BillingWalletClient.WalletCheck check;
        try {
            check = billingWalletClient.check(tenantUuid, projectId);
        } catch (BillingWalletClient.WalletBalanceCheckException ex) {
            log.warn("Wallet balance check failed tenantId={} errorMessage={}", tenantId, ex.getMessage());
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to verify wallet balance");
        }
        if (check.balance().compareTo(minimumWalletBalance) < 0) {
            throw GatewayException.paymentRequired(
                    "Insufficient wallet balance. currentBalance=%s minimumRequired=%s".formatted(check.balance(), minimumWalletBalance));
        }
        if (Boolean.FALSE.equals(check.projectSpendOk())) {
            throw GatewayException.paymentRequired(
                    "Project spend cap exceeded. projectId=%s accumulatedSpend=%s".formatted(projectId, check.projectSpendTotal()));
        }
    }

    /** Doc §2.1: when {@code taskKey} is set, resolve+render the active {@link PromptTemplate}
     * and prepend it as the system message, ahead of whatever the caller sent -- the caller holds
     * no instruction-text string literals of its own. A plain {@code messages}-only request
     * (taskKey absent) is returned unchanged. */
    private java.util.List<ChatMessage> effectiveMessages(ChatRequest request) {
        if (request.taskKey() == null || request.taskKey().isBlank()) {
            return request.messages();
        }
        String rendered = promptTemplateService.renderActive(request.taskKey(), request.templateVariables());
        java.util.List<ChatMessage> withTemplate = new java.util.ArrayList<>();
        withTemplate.add(new ChatMessage("system", rendered));
        withTemplate.addAll(request.messages());
        return withTemplate;
    }

    /** Safety margin added on top of the model's own timeout before a concurrency lease
     * self-expires -- must outlive {@code future.get()}'s own wait (timeoutMs + 5000ms) so a
     * genuinely still-running call is never evicted and double-counted by the next acquire. */
    private static final long LEASE_TTL_MARGIN_SECONDS = 30;

    /** Purely for observability (see LlmJob#getRequestContent's javadoc) -- never let a
     * serialization hiccup here cost the actual generation call. */
    private void recordRequestBestEffort(UUID jobId, java.util.List<ChatMessage> renderedMessages) {
        try {
            jobPersistenceService.recordRequest(jobId, objectMapper.writeValueAsString(renderedMessages));
        } catch (Exception ex) {
            log.warn("Could not record request content for jobId={}: {}", jobId, ex.getMessage());
        }
    }

    private ChatResponse dispatch(LlmJob job, String tenantId, ChatRequest request, int attemptNumber) {
        long startedAt = System.currentTimeMillis();
        CompletableFuture<LlmResponse> future = null;
        String providerId = null;
        boolean leaseAcquired = false;
        try {
            RoutedModel routed = modelRouterService.route(tenantId, job.getModelId());
            int tenantRpm = modelRouterService.tenantRpmOverride(tenantId, routed.model().getModelId())
                    .orElseGet(rateLimiterService::defaultTenantRpm);
            rateLimiterService.checkAndConsume(routed.model().getModelId(), routed.model().getDefaultRpm(), tenantId, tenantRpm);

            // RPM admits the request; this caps how many admitted calls may be simultaneously
            // blocked inside the provider at once -- the concurrency gate the token bucket alone
            // doesn't provide (see ProviderConcurrencyLeaseService).
            providerId = routed.model().getProviderId();
            int maxConcurrent = modelRouterService.effectiveMaxConcurrent(tenantId, routed);
            long leaseTtlSeconds = (routed.model().getTimeoutMs() / 1000L) + LEASE_TTL_MARGIN_SECONDS;
            leaseAcquired = concurrencyLeaseService.tryAcquire(providerId, job.getJobId(), maxConcurrent, leaseTtlSeconds);
            if (!leaseAcquired) {
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                        "provider_id=%s is at its concurrency limit (%d in-flight) -- try again shortly"
                                .formatted(providerId, maxConcurrent));
            }

            LlmProvider provider = providerRegistry.resolve(providerId);
            var renderedMessages = effectiveMessages(request);
            recordRequestBestEffort(job.getJobId(), renderedMessages);
            future = provider.generate(new CanonicalRequest(
                    routed.model().getModelId(), routed.model().getType(), renderedMessages, request.params(),
                    routed.model().getTimeoutMs(), request.tools()
            )).toFuture();
            inFlightJobRegistry.register(job.getJobId(), future);

            // Provider's own .timeout() is the real deadline; this is a safety margin on top so
            // a Mono that somehow never signals doesn't hang the servlet thread forever.
            LlmResponse response = future.get(routed.model().getTimeoutMs() + 5000L, TimeUnit.MILLISECONDS);
            long latencyMs = System.currentTimeMillis() - startedAt;
            BigDecimal cost = computeCost(routed.rateCard(), routed.model().getType(),
                    response.inputTokens(), response.outputTokens(), request.params());
            jobPersistenceService.finish(job.getJobId(), JobStatus.COMPLETED, null,
                    response.inputTokens(), response.outputTokens(), cost, (int) latencyMs, response.content());
            publishBillingEvent(job, response.inputTokens(), response.outputTokens(), cost, JobStatus.COMPLETED);
            return new ChatResponse(job.getJobId(), routed.model().getModelId(), response.content(),
                    new UsageDto(response.inputTokens(), response.outputTokens(), cost), latencyMs, response.toolCalls(), response.finishReason());
        } catch (CancellationException ex) {
            // cancel() may already have finalised this row -- finalizeIfStillProcessing() is a
            // no-op if so, so whichever thread gets there first "wins" without double-writing.
            jobPersistenceService.finalizeIfStillProcessing(job.getJobId(), JobStatus.CANCELLED, "Cancelled by user");
            throw GatewayException.conflict("job_id=" + job.getJobId() + " was cancelled");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            boolean retryable = cause instanceof LlmProviderException lpe && lpe.isRetryable();
            failJob(job, startedAt, cause.getMessage());
            throw new GatewayException(retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, cause.getMessage());
        } catch (TimeoutException ex) {
            if (future != null) {
                future.cancel(true);
            }
            failJob(job, startedAt, "Gateway-level timeout waiting for provider");
            throw new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "Provider did not respond within timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failJob(job, startedAt, "Interrupted while waiting for provider");
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while waiting for provider");
        } catch (LlmProviderException ex) {
            // Provider adapter/registry resolution failed before a future ever existed.
            failJob(job, startedAt, ex.getMessage());
            throw new GatewayException(ex.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (GatewayException ex) {
            // Routing (model deactivated/tenant disentitled since the job was claimed) or rate
            // limiting rejected the request -- the job was already claimed PROCESSING, so it
            // must be finalised here too, or it stays stuck forever (this was a real bug: these
            // failures used to happen before any try/catch existed around them).
            failJob(job, startedAt, ex.getMessage());
            throw ex;
        } finally {
            if (leaseAcquired) {
                concurrencyLeaseService.release(providerId, job.getJobId());
            }
        }
    }

    private void failJob(LlmJob job, long startedAt, String error) {
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        jobPersistenceService.finish(job.getJobId(), JobStatus.FAILED, error, 0, 0, BigDecimal.ZERO, latencyMs);
        publishBillingEvent(job, 0, 0, BigDecimal.ZERO, JobStatus.FAILED);
    }

    /** Best-effort cancel of a job's provider call. See {@link InFlightJobRegistry}. */
    public JobStatusResponse cancel(String tenantId, UUID jobId) {
        LlmJob job = llmJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> GatewayException.notFound("Unknown job_id: " + jobId));

        if (job.getStatus().isTerminal()) {
            throw GatewayException.badRequest("Cannot cancel job_id=%s, already %s".formatted(jobId, job.getStatus()));
        }
        if (inFlightJobRegistry.cancel(jobId)) {
            jobPersistenceService.finalizeIfStillProcessing(jobId, JobStatus.CANCELLED, "Cancelled by user");
            return jobStatus(tenantId, jobId);
        }
        if (isStale(job)) {
            // Not trackable on this pod and past its timeout -- presumed dead already (crash
            // recovery), so cancelling it is just making that explicit.
            jobPersistenceService.finalizeIfStillProcessing(jobId, JobStatus.TIMED_OUT,
                    "Stranded PROCESSING row exceeded staleness threshold, presumed crashed");
            return jobStatus(tenantId, jobId);
        }
        throw GatewayException.badRequest(
                "job_id=%s is processing but not trackable on this pod yet -- try again shortly".formatted(jobId));
    }

    /** Doc §14: projected cost from the rate card + a rough token count, no provider dispatch. */
    public EstimateResponse estimate(String tenantId, ChatRequest request) {
        RoutedModel routed = modelRouterService.route(tenantId, request.modelId());
        int estimatedInputTokens = estimateTokens(request);
        BigDecimal estimatedCost = routed.rateCard().getInputTokenCost().multiply(BigDecimal.valueOf(estimatedInputTokens));
        return new EstimateResponse(routed.model().getModelId(), estimatedInputTokens, estimatedCost, routed.rateCard().getRateCardId());
    }

    public JobStatusResponse jobStatus(String tenantId, UUID jobId) {
        LlmJob job = llmJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> GatewayException.notFound("Unknown job_id: " + jobId));
        // v1 does not persist response content anywhere (MinIO full-payload storage is
        // deferred, doc §15) -- a status poll can confirm completion/cost but not replay text.
        return new JobStatusResponse(job.getJobId(), job.getStatus().name(), null, job.getLastError(), null);
    }

    /** A PROCESSING row whose current attempt has run far longer than the model's own timeout
     * budget allows is presumed crashed, not genuinely slow (see {@link #STALENESS_MULTIPLIER}). */
    private boolean isStale(LlmJob job) {
        if (job.getProcessingStartedAt() == null) {
            return true;
        }
        var model = modelRouterService.route(job.getTenantId(), job.getModelId()).model();
        long staleAfterMs = (long) model.getTimeoutMs() * STALENESS_MULTIPLIER;
        return Duration.between(job.getProcessingStartedAt(), OffsetDateTime.now()).toMillis() > staleAfterMs;
    }

    // chars/4 heuristic -- a placeholder for a real tokenizer, doc §4/§14 both call for
    // pre-flight TPM/cost estimates but don't mandate a specific tokenizer implementation.
    private int estimateTokens(ChatRequest request) {
        int totalChars = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length())
                .sum();
        return Math.max(1, totalChars / 4);
    }

    private void publishBillingEvent(LlmJob job, int inputTokens, int outputTokens, BigDecimal cost, JobStatus status) {
        billingEventPublisher.publish(new BillingEvent(
                UUID.randomUUID(), job.getJobId(), job.getTenantId(), job.getProjectId(), job.getModelId(),
                inputTokens, outputTokens, cost, "USD", status.name(), OffsetDateTime.now()
        ));
    }

    /** Video, upscale, and music are all duration-priced, not token-priced: fal.ai/ElevenLabs
     * report 0/0 tokens for these (or, for ElevenLabs music, real credits don't map to a token
     * concept at all), so the token path always yielded $0. When the model's rate_card carries a
     * {@code per_second_cost}, cost = perSecondCost × duration_seconds (from request params --
     * the caller must supply this; see LlmGatewayUpscaleGenerationService for the video/upscale
     * case, ElevenLabsProvider.composeMusic's {@code music_length_ms} param for music). Every
     * other model type keeps the input/output-token path unchanged. */
    private static final java.util.Set<String> DURATION_PRICED_TYPES = java.util.Set.of("video", "upscale", "music", "lip_sync");

    static BigDecimal computeCost(RateCard rateCard, String modelType, int inputTokens, int outputTokens,
                                   Map<String, Object> params) {
        if (DURATION_PRICED_TYPES.contains(modelType) && rateCard.getPerSecondCost() != null) {
            BigDecimal seconds = durationSeconds(params);
            if (seconds.signum() > 0) {
                return rateCard.getPerSecondCost().multiply(seconds);
            }
        }
        BigDecimal inputCost = rateCard.getInputTokenCost().multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal outputCost = rateCard.getOutputTokenCost().multiply(BigDecimal.valueOf(outputTokens));
        return inputCost.add(outputCost);
    }

    /** fal.ai's video/upscale params carry clip length as {@code duration_seconds} (see
     * FalAiProvider); ElevenLabs' music endpoint uses its own real param name {@code
     * music_length_ms} instead (milliseconds, see ElevenLabsProvider.composeMusic) -- checked as
     * a fallback so this one helper covers both real param shapes rather than requiring every
     * caller to normalize into a param name that isn't the real one it sends fal.ai/ElevenLabs.
     * Tolerant of Integer/String/absent. */
    private static BigDecimal durationSeconds(Map<String, Object> params) {
        if (params == null) {
            return BigDecimal.ZERO;
        }
        Object raw = params.getOrDefault("duration_seconds", params.get("duration"));
        if (raw != null) {
            try {
                return new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ex) {
                return BigDecimal.ZERO;
            }
        }
        Object musicLengthMs = params.get("music_length_ms");
        if (musicLengthMs != null) {
            try {
                return new BigDecimal(String.valueOf(musicLengthMs)).divide(BigDecimal.valueOf(1000), 3, java.math.RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }
}
