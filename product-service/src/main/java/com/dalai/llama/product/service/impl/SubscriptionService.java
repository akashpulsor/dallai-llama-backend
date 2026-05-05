package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.client.BillingServiceClient.RecurringChargeRequest;
import com.dalai.llama.product.client.TenantServiceClient;
import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.domain.entity.enums.SagaStep;
import com.dalai.llama.product.domain.entity.enums.ChannelDirection;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.product.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.product.domain.event.WalletDeductedForSubscriptionEvent;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.dto.request.SubscriptionRequest;
import com.dalai.llama.product.dto.response.SubscriptionResponse;
import com.dalai.llama.product.dto.response.SubscriptionResponse.*;
import com.dalai.llama.product.kafka.producer.ProductEventProducer;
import com.dalai.llama.product.repository.*;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.SipEndpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionSagaRepository sagaRepository;
    private final ProductRepository productRepository;
    private final PlanRepository planRepository;
    private final PlanAssignmentRepository planAssignmentRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final DidRepository didRepository;
    private final SipTrunkRepository sipTrunkRepository;
    private final PstnChannelBundleRepository channelBundleRepository;
    private final ProductAppRepository productAppRepository;
    private final TenantSipTrunkRepository tenantSipTrunkRepository;

    private final SipEndpointService sipEndpointService;
    private final TenantSipTrunkService tenantSipTrunkService;
    private final EntitlementService entitlementService;
    private final BillingServiceClient billingClient;
    private final TenantServiceClient tenantClient;
    private final ProductEventProducer productEventProducer;




    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        UUID tenantId = request.getTenantId();

        log.info("Initiating subscription for tenant {} - product: {}, plan: {}, did: {}",
                tenantId, request.getProductCode(), request.getPlanCode(),
                request.getDid().getNumber());

        Product product = productRepository.findByCode(request.getProductCode())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductCode()));

        Plan plan = planRepository.findByCode(request.getPlanCode())
                .orElseThrow(() -> new PlanNotFoundException(request.getPlanCode()));

        if (!plan.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException(
                    "Plan " + request.getPlanCode() + " does not belong to product "
                            + request.getProductCode());
        }

        String didNumber = request.getDid().getNumber();
        didRepository.findByNumber(didNumber).ifPresent(existingDid -> {
            if (existingDid.getStatus() != DidStatus.RELEASED
                    && existingDid.getStatus() != DidStatus.AVAILABLE) {
                throw new IllegalStateException(
                        "DID " + didNumber + " is already reserved (status: " + existingDid.getStatus() + ")");
            }
        });

        // Calculate amounts
        BigDecimal subscriptionAmount = calculateTotal(plan, request);
        BigDecimal walletTopUp = plan.getMinimumWalletBalance() != null
                ? plan.getMinimumWalletBalance() : BigDecimal.valueOf(500);
        BigDecimal totalRequired = subscriptionAmount.add(walletTopUp);

        // ─── PRE-CHECK WALLET BALANCE ──────────────────────────────
        BillingServiceClient.WalletBalanceResponse currentBalance = billingClient.getCurrentBalance(tenantId);
        if (currentBalance.balance().compareTo(totalRequired) < 0) {
            log.info("Insufficient balance for tenant {} — required: {}, current: {}",
                    tenantId, totalRequired, currentBalance);
            return SubscriptionResponse.builder()
                    .status("INSUFFICIENT_BALANCE")
                    .provisioningStatus("RECHARGE_REQUIRED")
                    .requiredAmount(totalRequired)
                    .currentWalletBalance(currentBalance.balance())
                    .shortFallAmount(totalRequired.subtract(currentBalance.balance()))
                    .currency(currentBalance.currency())
                    .did(mapDid(request.getDid()))
                    .plan(mapPlan(plan, null))
                    .build();
        }
        Subscription subscription = Subscription.builder()
                .tenantId(tenantId)
                .product(product)
                .plan(plan)
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .agentSeats(request.getAgentCount() != null
                        ? request.getAgentCount() : plan.getIncludedAgents())
                .includedMinutes(plan.getIncludedMinutes() != null
                        ? plan.getIncludedMinutes() : 0)
                .requestedDidNumber(request.getDid().getNumber())
                .requestedDidCountry(request.getDid().getCountry())
                .requestedDidRegion(request.getDid().getRegion())
                .requestedDidCity(request.getDid().getCity())
                .build();
        subscription = subscriptionRepository.save(subscription);


        BillingServiceClient.SubscriptionPaymentResponse payment =
                billingClient.createSubscriptionPayment(
                        tenantId, plan.getCode(), subscriptionAmount,
                        walletTopUp, subscription.getId());

        log.info("Payment initiated for subscription {} order {}",
                subscription.getId(), payment.gatewayOrderId());

        return SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .status(SubscriptionStatus.PENDING_PAYMENT.name())
                .provisioningStatus("PAYMENT_PENDING")
                .requiredAmount(payment.totalAmount())
                .paymentId(payment.paymentId())
                .gatewayOrderId(payment.gatewayOrderId())
                .currency(payment.currency())
                .did(mapDid(request.getDid()))
                .plan(mapPlan(plan, null))
                .build();
    }

    /**
     * Called when tenant-service completes provisioning successfully.
     */
    @Transactional
    public void markProvisioned(UUID subscriptionId, UUID tenantAppId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        subscription.setTenantAppId(tenantAppId);
        subscription.setStatus(SubscriptionStatus.ACTIVE);

        subscriptionRepository.save(subscription);

        log.info("Subscription {} marked ACTIVE with tenantAppId={}", subscriptionId, tenantAppId);
    }

    /**
     * Called when tenant-service provisioning fails.
     */
    @Transactional
    public void markProvisioningFailed(UUID subscriptionId, String reason) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        subscription.setStatus(SubscriptionStatus.PROVISIONING_FAILED);

        subscriptionRepository.save(subscription);

        log.warn("Subscription {} marked PROVISIONING_FAILED: {}", subscriptionId, reason);
    }

    /**
     * Get full subscription details by ID.
     */
    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscriptionDetails(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        Plan plan = subscription.getPlan();
        Product product = subscription.getProduct();

        Did did = subscription.getDidId() != null
                ? didRepository.findById(subscription.getDidId()).orElse(null) : null;

        TenantSipTrunk tenantSipTrunk = subscription.getTenantSipTrunkId() != null
                ? tenantSipTrunkRepository.findById(subscription.getTenantSipTrunkId()).orElse(null) : null;

        PstnChannelBundle channels = subscription.getChannelBundleId() != null
                ? channelBundleRepository.findById(subscription.getChannelBundleId()).orElse(null) : null;

        String provisioningStatus;
        if (subscription.isFullyProvisioned()) {
            provisioningStatus = "COMPLETED";
        } else if (subscription.getStatus() == SubscriptionStatus.PROVISIONING_FAILED) {
            provisioningStatus = "FAILED";
        } else if (subscription.getStatus() == SubscriptionStatus.PENDING_PROVISION) {
            provisioningStatus = "IN_PROGRESS";
        } else {
            provisioningStatus = subscription.getStatus().name();
        }

        SubscriptionResponse.SubscriptionResponseBuilder builder = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .tenantAppId(subscription.getTenantAppId())
                .status(subscription.getStatus().name())
                .provisioningStatus(provisioningStatus)
                .plan(mapPlan(plan, subscription.getExpiresAt()));

        if (did != null) {
            builder.did(mapDid(did));
        }
        if (tenantSipTrunk != null) {
            builder.sipIntegration(mapSipIntegration(tenantSipTrunk));
        }
        if (channels != null) {
            builder.channels(mapChannels(channels));
        }

        return builder.build();
    }

    /**
     * SAGA: Triggered by Kafka WalletDeductedForSubscriptionEvent.
     * Idempotent via event ID dedup + saga state checks.
     */
    @Transactional
    public void processWalletDeducted(WalletDeductedForSubscriptionEvent event) {

        // Idempotency: skip if event already processed
        if (sagaRepository.existsByTriggeredByEventId(event.eventId())) {
            log.info("Event {} already processed — skipping (Kafka redelivery)", event.eventId());
            return;
        }

        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + event.subscriptionId()));

        // Get or create saga
        SubscriptionSaga saga = sagaRepository.findBySubscriptionId(event.subscriptionId())
                .orElseGet(() -> createSaga(event));

        if (saga.getCurrentStep() == SagaStep.COMPLETED) {
            log.info("Saga for subscription {} already COMPLETED — skipping", event.subscriptionId());
            return;
        }

        runSaga(saga, subscription);
    }

    /**
     * Manual retry endpoint — resumes saga from last completed step.
     */
    /**
     * Manual retry endpoint — resumes saga from last completed step.
     */
    @Transactional
    public SubscriptionResponse retryProvisioning(UUID subscriptionId) {
        SubscriptionSaga saga = sagaRepository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("No saga for subscription " + subscriptionId));

        if (saga.getCurrentStep() != SagaStep.FAILED) {
            throw new IllegalStateException("Saga not in FAILED state: " + saga.getCurrentStep());
        }

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        // Reset for retry
        saga.setRetryCount(saga.getRetryCount() + 1);
        saga.setFailedStep(null);
        saga.setFailureReason(null);
        saga.setFailedAt(null);
        saga.setCurrentStep(saga.getLastCompletedStep() != null
                ? saga.getLastCompletedStep() : SagaStep.PENDING);
        sagaRepository.save(saga);

        // Run saga
        runSaga(saga, subscription);

        // Reload to get final state
        saga = sagaRepository.findBySubscriptionId(subscriptionId).orElseThrow();
        subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();

        return buildRetryResponse(saga, subscription);
    }

    /**
     * Build response based on final saga state after retry.
     */
    private SubscriptionResponse buildRetryResponse(SubscriptionSaga saga, Subscription subscription) {
        Plan plan = subscription.getPlan();

        Did did = saga.getDidId() != null
                ? didRepository.findById(saga.getDidId()).orElse(null) : null;

        PstnChannelBundle channels = saga.getChannelBundleId() != null
                ? channelBundleRepository.findById(saga.getChannelBundleId()).orElse(null) : null;

        TenantSipTrunk tenantSipTrunk = saga.getTenantSipTrunkId() != null
                ? tenantSipTrunkRepository.findById(saga.getTenantSipTrunkId()).orElse(null) : null;

        String provisioningStatus = switch (saga.getCurrentStep()) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED_AT_" + (saga.getFailedStep() != null ? saga.getFailedStep().name() : "UNKNOWN");
            default -> "IN_PROGRESS";
        };

        SubscriptionResponse.SubscriptionResponseBuilder builder = SubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .tenantAppId(subscription.getTenantAppId())
                .status(subscription.getStatus().name())
                .provisioningStatus(provisioningStatus)
                .plan(mapPlan(plan, subscription.getExpiresAt()));

        if (did != null) {
            builder.did(mapDid(did));
        }

        if (tenantSipTrunk != null) {
            builder.sipIntegration(mapSipIntegration(tenantSipTrunk));
        }

        if (channels != null) {
            builder.channels(mapChannels(channels));
        }

        return builder.build();
    }
    private SubscriptionSaga createSaga(WalletDeductedForSubscriptionEvent event) {
        SubscriptionSaga saga = SubscriptionSaga.builder()
                .id(UUID.randomUUID())
                .subscriptionId(event.subscriptionId())
                .tenantId(event.tenantId())
                .paymentId(event.paymentId())
                .triggeredByEventId(event.eventId())
                .currentStep(SagaStep.PENDING)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .retryCount(0)
                .build();
        return sagaRepository.save(saga);
    }

    private void runSaga(SubscriptionSaga saga, Subscription subscription) {
        Product product = subscription.getProduct();
        Plan plan = subscription.getPlan();

        PlanEntitlement entitlement = planEntitlementRepository.findByPlan_Id(plan.getId())
                .orElseThrow(() -> new RuntimeException("Plan entitlements not found"));

        Did did = null;
        SipEndpoint sipEndpoint = null;
        PstnChannelBundle channels = null;
        TenantSipTrunk tenantSipTrunk = null;
        PlanAssignment assignment = null;

        // Mark subscription as PROVISIONING
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            subscription.markPendingProvision();
            subscriptionRepository.save(subscription);
        }

        try {
            // ─── STEP 1: DID ──────────────────────────────────────────
            if (saga.getDidId() != null) {
                did = didRepository.findById(saga.getDidId()).orElse(null);
                log.info("[SAGA] Reusing existing DID for subscription {}", saga.getSubscriptionId());
            }
            if (did == null) {
                did = provisionDid(saga.getTenantId(), product.getCode(),
                        SubscriptionRequest.DidInfo.builder()
                                .number(subscription.getRequestedDidNumber())
                                .country(subscription.getRequestedDidCountry())
                                .region(subscription.getRequestedDidRegion())
                                .city(subscription.getRequestedDidCity())


                                .build());
                saga.setDidId(did.getId());
                subscription.markDidProvisioned(did.getId());
                subscriptionRepository.save(subscription);
                log.info("[SAGA] DID provisioned for subscription {}", saga.getSubscriptionId());
            }
            saga.advanceTo(SagaStep.DID_PROVISIONED);
            sagaRepository.save(saga);

            // ─── STEP 2: SIP Endpoint ────────────────────────────────
            if (saga.getSipEndpointId() != null) {
                sipEndpoint = sipEndpointService.getById(saga.getSipEndpointId());
            }
            if (sipEndpoint == null) {
                sipEndpoint = sipEndpointService.createEndpoint(did);
                saga.setSipEndpointId(sipEndpoint.getId());
                subscription.markSipEndpointCreated(sipEndpoint.getId());
                subscriptionRepository.save(subscription);
                log.info("[SAGA] SIP endpoint created");
            }
            saga.advanceTo(SagaStep.SIP_ENDPOINT_CREATED);
            sagaRepository.save(saga);

            // ─── STEP 3: Channels ────────────────────────────────────
            if (saga.getChannelBundleId() != null) {
                channels = channelBundleRepository.findById(saga.getChannelBundleId()).orElse(null);
            }
            if (channels == null) {
                channels = createChannelBundle(saga.getTenantId(), subscription.getId(),
                        product.getCode(), plan, entitlement, null);
                saga.setChannelBundleId(channels.getId());
                subscription.markChannelsAllocated(channels.getId());
                subscriptionRepository.save(subscription);
                log.info("[SAGA] Channels allocated");
            }
            saga.advanceTo(SagaStep.CHANNELS_ALLOCATED);
            sagaRepository.save(saga);

            // ─── STEP 4: Tenant SIP Trunk ────────────────────────────
            if (saga.getTenantSipTrunkId() != null) {
                tenantSipTrunk = tenantSipTrunkRepository.findById(saga.getTenantSipTrunkId()).orElse(null);
            }
            if (tenantSipTrunk == null) {
                TenantServiceClient.TenantInfo tenant = tenantClient.getTenant(saga.getTenantId());
                tenantSipTrunk = tenantSipTrunkService.createForSubscription(
                        saga.getTenantId(), subscription.getId(), tenant.slug(),
                        channels.getTotalChannels());
                saga.setTenantSipTrunkId(tenantSipTrunk.getId());
                subscription.setTenantSipTrunkId(tenantSipTrunk.getId());
                subscriptionRepository.save(subscription);
                log.info("[SAGA] Tenant SIP trunk created");
            }
            saga.advanceTo(SagaStep.TENANT_TRUNK_CREATED);
            sagaRepository.save(saga);

            // ─── STEP 5: Plan Assignment ─────────────────────────────
            if (saga.getPlanAssignmentId() != null) {
                assignment = planAssignmentRepository.findById(saga.getPlanAssignmentId()).orElse(null);
            }
            if (assignment == null) {
                assignment = assignPlan(saga.getTenantId(), subscription.getId(),
                        plan, subscription.getAgentSeats());
                saga.setPlanAssignmentId(assignment.getId());
                subscription.setPlanAssignmentId(assignment.getId());
                subscriptionRepository.save(subscription);
                log.info("[SAGA] Plan assigned");
            }
            saga.advanceTo(SagaStep.PLAN_ASSIGNED);
            sagaRepository.save(saga);

            // ─── STEP 6: Activate ────────────────────────────────────
            subscription.activate();
            subscriptionRepository.save(subscription);
            saga.advanceTo(SagaStep.ACTIVATED);
            sagaRepository.save(saga);
            log.info("[SAGA] Subscription {} activated", saga.getSubscriptionId());

            // ─── STEP 7: Recurring charges ───────────────────────────
            createRecurringCharges(saga.getTenantId(), subscription.getId(),
                    plan, did, subscription.getAgentSeats());
            saga.advanceTo(SagaStep.RECURRING_CHARGES_CREATED);
            sagaRepository.save(saga);

            // ─── Cache invalidation ──────────────────────────────────
            entitlementService.invalidateCache(saga.getTenantId());

            // ─── Mark complete + Publish success event ───────────────
            saga.markCompleted();
            sagaRepository.save(saga);

            SipTrunk platformTrunk = sipTrunkRepository.findPlatformTrunk().orElse(null);
            publishActivationSuccessEvent(subscription, product, plan, entitlement,
                    did, sipEndpoint, channels, tenantSipTrunk, platformTrunk);

            log.info("[SAGA] Subscription {} fully provisioned", saga.getSubscriptionId());

        } catch (Exception e) {
            log.error("[SAGA] FAILED at step {} for subscription {}: {}",
                    saga.getCurrentStep(), saga.getSubscriptionId(), e.getMessage(), e);

            saga.markFailed(saga.getCurrentStep(), e.getMessage());
            sagaRepository.save(saga);

            try {
                subscription.setStatus(SubscriptionStatus.FAILED);
                subscriptionRepository.save(subscription);
            } catch (Exception ex) {
                log.error("Could not mark subscription FAILED", ex);
            }

            compensate(saga);

            try {
                publishActivationFailedEvent(saga, e);
            } catch (Exception ex) {
                log.error("Failed to publish failure event", ex);
            }
        }
    }

    /**
     * Compensate executed steps in REVERSE order using saga's resource refs.
     */
    private void compensate(SubscriptionSaga saga) {
        log.warn("[SAGA-COMPENSATE] Rolling back saga {} (failed at {})",
                saga.getId(), saga.getFailedStep());

        if (saga.getPlanAssignmentId() != null) {
            safeRun(() -> planAssignmentRepository.deleteById(saga.getPlanAssignmentId()),
                    "delete plan assignment");
        }
        if (saga.getTenantSipTrunkId() != null) {
            safeRun(() -> tenantSipTrunkRepository.deleteById(saga.getTenantSipTrunkId()),
                    "delete tenant SIP trunk");
        }
        if (saga.getChannelBundleId() != null) {
            safeRun(() -> channelBundleRepository.deleteById(saga.getChannelBundleId()),
                    "delete channel bundle");
        }
        if (saga.getSipEndpointId() != null) {
            safeRun(() -> sipEndpointService.delete(saga.getSipEndpointId()),
                    "delete SIP endpoint");
        }
        if (saga.getDidId() != null) {
            safeRun(() -> didRepository.deleteById(saga.getDidId()),
                    "delete DID");
        }
        log.info("[SAGA-COMPENSATE] Compensation completed");
    }

    private void safeRun(Runnable action, String description) {
        try {
            action.run();
            log.info("[SAGA-COMPENSATE] {}: OK", description);
        } catch (Exception e) {
            log.error("[SAGA-COMPENSATE] {}: FAILED — {}", description, e.getMessage());
        }
    }

    private DidDetails mapDid(Did did) {
        if (did == null) return null;
        return DidDetails.builder()
                .id(did.getId())
                .number(did.getNumber())
                .displayNumber(did.getDisplayNumber())

                .country(did.getCountry())
                .region(did.getRegion())
                .city(did.getCity())
                .status(did.getStatus().name())
                .build();
    }

    private SipIntegration mapSipIntegration(TenantSipTrunk trunk) {
        if (trunk == null) return null;
        return SipIntegration.builder()
                .server(trunk.getDomain())
                .port(trunk.getPort())
                .transport(trunk.getTransport().name())
                .username(trunk.getUsername())
                .password(trunk.getPasswordPlain())
                .realm(trunk.getRealm())
                .registrarUri("sip:" + trunk.getDomain() + ":" + trunk.getPort())
                .maxConcurrentCalls(trunk.getMaxConcurrentCalls())
                .build();
    }

    private ChannelDetails mapChannels(PstnChannelBundle channels) {
        if (channels == null) return null;
        return ChannelDetails.builder()
                .id(channels.getId())
                .direction(channels.getDirection().name())
                .totalChannels(channels.getTotalChannels())
                .inboundChannels(channels.getInboundChannels())
                .outboundChannels(channels.getOutboundChannels())
                .status(channels.getStatus())
                .build();
    }
    private void publishActivationSuccessEvent(
            Subscription subscription, Product product, Plan plan, PlanEntitlement entitlement,
            Did did, SipEndpoint sipEndpoint, PstnChannelBundle channels,
            TenantSipTrunk tenantSipTrunk, SipTrunk platformTrunk) {

        log.info("Publishing SubscriptionActivatedEvent for {}", subscription.getId());

        List<SubscriptionActivatedEvent.ProductAppData> productApps = productAppRepository
                .findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(product.getId())
                .stream()
                .map(app -> SubscriptionActivatedEvent.ProductAppData.builder()
                        .appType(app.getAppType().name())
                        .displayName(app.getDisplayName())
                        .subdomain(app.getSubdomain())
                        .icon(app.getIcon())
                        .displayOrder(app.getDisplayOrder())
                        .build())
                .toList();

        SubscriptionActivatedEvent event = SubscriptionActivatedEvent.builder()
                .subscriptionId(subscription.getId())
                .tenantId(subscription.getTenantId())
                .status(subscription.getStatus().name())
                .activatedAt(subscription.getActivatedAt())
                .expiresAt(subscription.getExpiresAt())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .planId(plan.getId())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .planTier(plan.getTier().name())
                .monthlyPrice(plan.getMonthlyPrice())
                .aiRatePerMin(plan.getAiRatePerMin())
                .agentSeats(subscription.getAgentSeats())
                .maxAgents(entitlement.getMaxAgents())
                .maxDids(entitlement.getMaxDids())
                .maxChannels(entitlement.getMaxPstnChannels())
                .includedMinutes(subscription.getIncludedMinutes())
                .didId(did.getId())
                .didNumber(did.getNumber())
                .didDisplayNumber(did.getDisplayNumber())
                .didCountry(did.getCountry())
                .didRegion(did.getRegion())
                .didCity(did.getCity())
                .didMonthlyRental(did.getMonthlyRental())
                .sipEndpointId(sipEndpoint.getId())
                .sipEndpointUsername(sipEndpoint.getUsername())
                .sipEndpointPasswordHash(sipEndpoint.getPasswordHash())
                .sipEndpointDomain(sipEndpoint.getDomain())
                .sipEndpointRealm(sipEndpoint.getRealm())
                .channelBundleId(channels.getId())
                .channelDirection(channels.getDirection().name())
                .totalChannels(channels.getTotalChannels())
                .inboundChannels(channels.getInboundChannels())
                .outboundChannels(channels.getOutboundChannels())
                .tenantSipTrunkId(tenantSipTrunk.getId())
                .tenantSipTrunkUsername(tenantSipTrunk.getUsername())
                .tenantSipTrunkPasswordHash(tenantSipTrunk.getPasswordHash())
                .tenantSipTrunkPasswordPlain(tenantSipTrunk.getPasswordPlain())
                .tenantSipTrunkDomain(tenantSipTrunk.getDomain())
                .tenantSipTrunkPort(tenantSipTrunk.getPort())
                .tenantSipTrunkRealm(tenantSipTrunk.getRealm())
                .tenantSipTrunkTransport(tenantSipTrunk.getTransport().name())
                .tenantSipTrunkMaxConcurrentCalls(tenantSipTrunk.getMaxConcurrentCalls())
                .platformTrunkId(platformTrunk != null ? platformTrunk.getId() : null)
                .platformTrunkProvider(platformTrunk != null ? platformTrunk.getProvider().name() : null)
                .platformTrunkServer(platformTrunk != null ? platformTrunk.getServer() : null)
                .platformTrunkPort(platformTrunk != null ? platformTrunk.getPort() : null)
                .platformTrunkTransport(platformTrunk != null ? platformTrunk.getTransport().name() : null)
                .platformTrunkCodecs(platformTrunk != null ? platformTrunk.getCodecs() : null)
                .productApps(productApps)
                .build();

        productEventProducer.publishSubscriptionActivated(event);
    }

    private void publishActivationFailedEvent(SubscriptionSaga saga, Exception e) {
        SubscriptionActivationFailedEvent event = SubscriptionActivationFailedEvent.builder()
                .subscriptionId(saga.getSubscriptionId())
                .tenantId(saga.getTenantId())
                .paymentId(saga.getPaymentId())
                .failedAtStep(saga.getFailedStep() != null ? saga.getFailedStep().name() : "INIT")
                .reason(e.getMessage())
                .failedAt(Instant.now())
                .build();
        productEventProducer.publishSubscriptionFailed(event);
    }

    public Subscription getSubscription(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
    }

    public List<Subscription> getTenantSubscriptions(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId);
    }

    public List<Subscription> getTenantActiveSubscriptions(UUID tenantId) {
        return subscriptionRepository.findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE);
    }

    public List<Subscription> getSubscriptionsByProduct(UUID tenantId, String productCode) {
        Product product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException(productCode));
        return subscriptionRepository.findByTenantIdAndProductId(tenantId, product.getId());
    }

    // ==================== CANCEL SUBSCRIPTION ====================

    /**
     * Cancel a subscription: stop recurring billing, release DID, clean up resources.
     */
    @Transactional
    public void cancelSubscription(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            log.info("Subscription {} already cancelled", subscriptionId);
            return;
        }

        UUID tenantId = subscription.getTenantId();
        log.info("Cancelling subscription {} for tenant {}", subscriptionId, tenantId);

        // 1. Cancel recurring charges in billing-service
        try {
            billingClient.cancelRecurringCharges(tenantId, subscriptionId);
            log.info("Recurring charges cancelled for subscription {}", subscriptionId);
        } catch (Exception e) {
            log.warn("Failed to cancel recurring charges (continuing): {}", e.getMessage());
        }

        // 2. Release DID
        if (subscription.getDidId() != null) {
            try {
                Did did = didRepository.findById(subscription.getDidId()).orElse(null);
                if (did != null && did.getStatus() != DidStatus.RELEASED) {
                    did.setStatus(DidStatus.RELEASING);
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);

                    // TODO: Call DIDWW API to release DID

                    did.setStatus(DidStatus.RELEASED);

                    did.setReleasedAt(Instant.now());
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);
                    log.info("DID {} released for subscription {}", did.getNumber(), subscriptionId);
                }
            } catch (Exception e) {
                log.warn("Failed to release DID (continuing): {}", e.getMessage());
            }
        }

        // 3. Delete SIP endpoint
        if (subscription.getSipEndpointId() != null) {
            try {
                sipEndpointService.delete(subscription.getSipEndpointId());
                log.info("SIP endpoint deleted for subscription {}", subscriptionId);
            } catch (Exception e) {
                log.warn("Failed to delete SIP endpoint (continuing): {}", e.getMessage());
            }
        }

        // 4. Delete channel bundle
        if (subscription.getChannelBundleId() != null) {
            try {
                channelBundleRepository.deleteById(subscription.getChannelBundleId());
                log.info("Channel bundle deleted for subscription {}", subscriptionId);
            } catch (Exception e) {
                log.warn("Failed to delete channel bundle (continuing): {}", e.getMessage());
            }
        }

        // 5. Delete tenant SIP trunk
        if (subscription.getTenantSipTrunkId() != null) {
            try {
                tenantSipTrunkRepository.deleteById(subscription.getTenantSipTrunkId());
                log.info("SIP trunk deleted for subscription {}", subscriptionId);
            } catch (Exception e) {
                log.warn("Failed to delete SIP trunk (continuing): {}", e.getMessage());
            }
        }

        // 6. Remove plan assignment
        if (subscription.getPlanAssignmentId() != null) {
            try {
                planAssignmentRepository.deleteById(subscription.getPlanAssignmentId());
                log.info("Plan assignment removed for subscription {}", subscriptionId);
            } catch (Exception e) {
                log.warn("Failed to remove plan assignment (continuing): {}", e.getMessage());
            }
        }

        // 7. Mark subscription cancelled
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(Instant.now());
        subscription.setUpdatedAt(Instant.now());
        subscriptionRepository.save(subscription);

        // 8. Invalidate entitlement cache
        entitlementService.invalidateCache(tenantId);

        log.info("Subscription {} cancelled for tenant {}", subscriptionId, tenantId);
    }

    // ==================== HELPERS (UNCHANGED) ====================

    private BigDecimal calculateTotal(Plan plan, SubscriptionRequest request) {
        BigDecimal platformFee = plan.getMonthlyPrice();
        int includedAgents = plan.getIncludedAgents() != null ? plan.getIncludedAgents() : 0;
        int requestedAgents = request.getAgentCount() != null ? request.getAgentCount() : includedAgents;
        int additionalAgents = Math.max(0, requestedAgents - includedAgents);
        BigDecimal perAgentFee = plan.getPerAgentFee() != null ? plan.getPerAgentFee() : BigDecimal.ZERO;
        BigDecimal agentFee = perAgentFee.multiply(BigDecimal.valueOf(additionalAgents));
        BigDecimal didSetup = request.getDid().getSetupFee() != null
                ? request.getDid().getSetupFee() : BigDecimal.ZERO;
        BigDecimal didMonthly = request.getDid().getMonthlyFee() != null
                ? request.getDid().getMonthlyFee() : BigDecimal.ZERO;
        return platformFee.add(agentFee).add(didSetup).add(didMonthly);
    }

    private Did provisionDid(UUID tenantId, String productCode, SubscriptionRequest.DidInfo didInfo) {
        SipTrunk trunk = sipTrunkRepository.findPlatformTrunk()
                .orElseThrow(() -> new RuntimeException("Platform trunk not configured"));

        Did did = Did.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .sipTrunk(trunk)
                .number(didInfo.getNumber())
                .displayNumber(formatDisplayNumber(didInfo.getNumber()))
                .country(didInfo.getCountry() != null ? didInfo.getCountry() : "IN")
                .region(didInfo.getRegion())
                .city(didInfo.getCity())
                .status(DidStatus.PENDING)
                .monthlyRental(didInfo.getMonthlyFee() != null ? didInfo.getMonthlyFee() : BigDecimal.valueOf(199))
                .currency("INR")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return didRepository.save(did);
    }

    private PstnChannelBundle createChannelBundle(UUID tenantId, UUID subscriptionId, String productCode,
                                                  Plan plan, PlanEntitlement entitlement,
                                                  SubscriptionRequest.ChannelConfig channelConfig) {
        int totalChannels = entitlement.getMaxPstnChannels();
        ChannelDirection direction = getDirectionForProduct(productCode);
// Default to 0 — DB has NOT NULL constraint
        int inbound = 0;
        int outbound = 0;

        if (channelConfig != null && channelConfig.getTotalChannels() != null) {
            totalChannels = channelConfig.getTotalChannels();
        }

        switch (direction) {
            case INBOUND -> inbound = totalChannels;
            case OUTBOUND -> outbound = totalChannels;
            case BOTH -> {
                if (channelConfig != null && channelConfig.getInboundChannels() != null
                        && channelConfig.getOutboundChannels() != null) {
                    inbound = channelConfig.getInboundChannels();
                    outbound = channelConfig.getOutboundChannels();
                } else {
                    inbound = (int) Math.ceil(totalChannels * 0.6);
                    outbound = totalChannels - inbound;
                }
            }
        }
        SipTrunk trunk = sipTrunkRepository.findPlatformTrunk().orElse(null);

        return channelBundleRepository.save(PstnChannelBundle.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .productCode(productCode)
                .sipTrunk(trunk)
                .direction(direction)
                .totalChannels(totalChannels)
                .inboundChannels(inbound)
                .outboundChannels(outbound)
                .activeChannels(0)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private ChannelDirection getDirectionForProduct(String productCode) {
        return switch (productCode) {
            case "CONV_IVR" -> ChannelDirection.INBOUND;
            case "OUTBOUND_DIALER" -> ChannelDirection.OUTBOUND;
            default -> ChannelDirection.BOTH;
        };
    }

    private PlanAssignment assignPlan(UUID tenantId, UUID subscriptionId, Plan plan, Integer agentCount) {
        return planAssignmentRepository.save(PlanAssignment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .plan(plan)
                .effectiveFrom(Instant.now())
                .active(true)
                .entitlementOverrides(agentCount != null ? java.util.Map.of("agentCount", agentCount) : null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private void createRecurringCharges(UUID tenantId, UUID subscriptionId, Plan plan, Did did, Integer agentCount) {
        billingClient.createRecurringCharge(tenantId,
                RecurringChargeRequest.platformFee(plan.getMonthlyPrice(), subscriptionId));

        if (did.getMonthlyRental() != null && did.getMonthlyRental().signum() > 0) {
            billingClient.createRecurringCharge(tenantId,
                    RecurringChargeRequest.didRental(did.getMonthlyRental(), did.getId(),
                            did.getNumber(), subscriptionId));
        }

        int included = plan.getIncludedAgents() != null ? plan.getIncludedAgents() : 0;
        int requested = agentCount != null ? agentCount : included;
        int additional = Math.max(0, requested - included);

        if (additional > 0 && plan.getPerAgentFee() != null) {
            BigDecimal agentFee = plan.getPerAgentFee().multiply(BigDecimal.valueOf(additional));
            billingClient.createRecurringCharge(tenantId,
                    RecurringChargeRequest.agentFee(agentFee, additional, subscriptionId));
        }
    }

    private DidDetails mapDid(SubscriptionRequest.DidInfo didInfo) {
        if (didInfo == null) return null;
        return DidDetails.builder()
                .number(didInfo.getNumber())
                .displayNumber(formatDisplayNumber(didInfo.getNumber()))
                .country(didInfo.getCountry() != null ? didInfo.getCountry() : "IN")
                .region(didInfo.getRegion())
                .city(didInfo.getCity())
                .status("PENDING_PROVISION")
                .build();
    }

    private PlanDetails mapPlan(Plan plan, Instant validUntil) {
        return PlanDetails.builder()
                .code(plan.getCode())
                .name(plan.getName())
                .tier(plan.getTier().name())
                .includedAgents(plan.getIncludedAgents() != null ? plan.getIncludedAgents() : 0)
                .includedMinutes(plan.getIncludedMinutes() != null ? plan.getIncludedMinutes() : 0)
                .validUntil(validUntil != null ? validUntil : Instant.now().plusSeconds(30L * 24 * 60 * 60))
                .build();
    }

    private String formatDisplayNumber(String number) {
        if (number == null) return number;
        // Handle +91XXXXXXXXXX
        if (number.startsWith("+91") && number.length() == 13) {
            return number.substring(0, 3) + " " + number.substring(3, 8) + " " + number.substring(8);
        }
        // Handle 91XXXXXXXXXX (no plus)
        if (number.startsWith("91") && number.length() == 12) {
            return "+91 " + number.substring(2, 7) + " " + number.substring(7);
        }
        return number;
    }
}