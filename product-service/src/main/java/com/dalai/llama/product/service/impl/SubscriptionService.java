package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.client.BillingServiceClient.RecurringChargeRequest;
import com.dalai.llama.product.client.TenantServiceClient;
import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.domain.entity.enums.ChannelDirection;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.product.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.dto.request.SubscriptionRequest;
import com.dalai.llama.product.dto.response.SubscriptionResponse;
import com.dalai.llama.product.dto.response.SubscriptionResponse.*;
import com.dalai.llama.product.kafka.producer.ProductEventProducer;
import com.dalai.llama.product.repository.*;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.SipEndpointService;
import com.dalai.llama.product.service.impl.TenantSipTrunkService;
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


    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        UUID tenantId = request.getTenantId();

        log.info("Initiating subscription checkout for tenant {} - product: {}, plan: {}, did: {}",
                tenantId,
                request.getProductCode(),
                request.getPlanCode(),
                request.getDid().getNumber());

        // 1. Validate product
        Product product = productRepository.findByCode(request.getProductCode())
                .orElseThrow(() ->
                        new ProductNotFoundException(request.getProductCode()));

        // 2. Validate plan
        Plan plan = planRepository.findByCode(request.getPlanCode())
                .orElseThrow(() ->
                        new PlanNotFoundException(request.getPlanCode()));

        if (!plan.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException(
                    "Plan " + request.getPlanCode()
                            + " does not belong to product "
                            + request.getProductCode()
            );
        }

        // 3. Check DID already active
        String didNumber = request.getDid().getNumber();

        didRepository.findByNumber(didNumber).ifPresent(existingDid -> {
            subscriptionRepository.findByDidId(existingDid.getId())
                    .ifPresent(existingSub -> {
                        if (existingSub.getStatus() == SubscriptionStatus.ACTIVE) {
                            throw new IllegalStateException(
                                    "DID " + didNumber + " is already in use"
                            );
                        }
                    });
        });

        // 4. Create pending subscription first
        Subscription subscription = Subscription.builder()
                .tenantId(tenantId)
                .product(product)
                .plan(plan)
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .agentSeats(
                        request.getAgentCount() != null
                                ? request.getAgentCount()
                                : plan.getIncludedAgents()
                )
                .includedMinutes(
                        plan.getIncludedMinutes() != null
                                ? plan.getIncludedMinutes()
                                : 0
                )
                .requestedDidNumber(request.getDid().getNumber())
                .requestedDidCountry(request.getDid().getCountry())
                .requestedDidRegion(request.getDid().getRegion())
                .requestedDidCity(request.getDid().getCity())
                .build();

        subscription = subscriptionRepository.save(subscription);

        // 5. Calculate amounts
        BigDecimal subscriptionAmount = calculateTotal(plan, request);

        BigDecimal walletTopUp =
                plan.getMinimumWalletBalance() != null
                        ? plan.getMinimumWalletBalance()
                        : BigDecimal.valueOf(500);

        BigDecimal totalAmount =
                subscriptionAmount.add(walletTopUp);

        // 6. Create payment order in billing service
        BillingServiceClient.SubscriptionPaymentResponse payment =
                billingClient.createSubscriptionPayment(
                        tenantId,
                        plan.getCode(),
                        subscriptionAmount,
                        walletTopUp,
                        subscription.getId()
                );

        log.info("Payment order created for subscription {} order {}",
                subscription.getId(),
                payment.gatewayOrderId());

        // 7. Return checkout response to customer
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

    @Transactional
    public SubscriptionResponse postSubscription(UUID subscriptionId) {

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (subscription.getStatus() != SubscriptionStatus.PENDING_PAYMENT
                && subscription.getStatus() != SubscriptionStatus.PENDING_PROVISION) {
            throw new IllegalStateException(
                    "Subscription is not eligible for post-payment provisioning"
            );
        }

        UUID tenantId = subscription.getTenantId();
        Product product = subscription.getProduct();
        Plan plan = subscription.getPlan();

        PlanEntitlement entitlement = planEntitlementRepository
                .findByPlan_Id(plan.getId())
                .orElseThrow(() ->
                        new RuntimeException("Plan entitlements not found"));

        // Move to pending provision
        subscription.markPendingProvision();
        subscriptionRepository.save(subscription);

        Did did = null;
        SipEndpoint sipEndpoint = null;
        PstnChannelBundle channels = null;
        TenantSipTrunk tenantSipTrunk = null;

        try {
            // 1. Provision DID
            did = provisionDid(
                    tenantId,
                    product.getCode(),
                    SubscriptionRequest.DidInfo.builder()
                            .number(subscription.getRequestedDidNumber())
                            .country(subscription.getRequestedDidCountry())
                            .region(subscription.getRequestedDidRegion())
                            .city(subscription.getRequestedDidCity())
                            .build()
            );

            subscription.markDidProvisioned(did.getId());
            subscriptionRepository.save(subscription);

            // 2. SIP endpoint
            sipEndpoint = sipEndpointService.createEndpoint(did);
            subscription.markSipEndpointCreated(sipEndpoint.getId());
            subscriptionRepository.save(subscription);

            // 3. Channels
            channels = createChannelBundle(
                    tenantId,
                    subscription.getId(),
                    product.getCode(),
                    plan,
                    entitlement,
                    null
            );

            subscription.markChannelsAllocated(channels.getId());
            subscriptionRepository.save(subscription);

            // 4. Tenant SIP trunk
            TenantServiceClient.TenantInfo tenant =
                    tenantClient.getTenant(tenantId);

            tenantSipTrunk =
                    tenantSipTrunkService.createForSubscription(
                            tenantId,
                            subscription.getId(),
                            tenant.slug()
                    );

            subscription.setTenantSipTrunkId(
                    tenantSipTrunk.getId()
            );

            subscriptionRepository.save(subscription);
            // Replace with Kafka / Outbox


        } catch (Exception e) {

            log.error("Provisioning failed for subscription {}: {}",
                    subscriptionId,
                    e.getMessage(),
                    e);

            // 🔥 IMPORTANT: mark FAILED
            subscription.setStatus(SubscriptionStatus.FAILED);
            subscriptionRepository.save(subscription);

            // 🔥 SAGA EVENT: trigger compensation (refund + cleanup)
            try {
                publishActivationFailedEvent(subscription, e);
            } catch (Exception ex) {
                log.error("Failed to publish failure event for subscription {}", subscriptionId, ex);
            }

            // 🔥 OPTIONAL immediate cleanup (defensive)
            safeCleanup(subscription, did, sipEndpoint, channels, tenantSipTrunk);

            return SubscriptionResponse.builder()
                    .subscriptionId(subscription.getId())
                    .status(subscription.getStatus().name())
                    .provisioningStatus("FAILED")
                    .did(did != null ? mapDid(did) : null)
                    .plan(mapPlan(plan, null))
                    .build();
        }

        // 5. Assign plan
        PlanAssignment assignment =
                assignPlan(
                        tenantId,
                        subscription.getId(),
                        plan,
                        subscription.getAgentSeats()
                );

        subscription.setPlanAssignmentId(assignment.getId());

        // 6. Activate
        subscription.activate();
        subscriptionRepository.save(subscription);

        // 7. Create recurring charges
        createRecurringCharges(
                tenantId,
                subscription.getId(),
                plan,
                did,
                subscription.getAgentSeats()
        );

        // 8. Notify tenant service
        SipTrunk platformTrunk =
                sipTrunkRepository.findPlatformTrunk().orElse(null);

        TenantServiceClient.SubscriptionActiveResponse tenantResponse =
                tenantClient.notifySubscriptionActive(
                        buildSubscriptionData(
                                subscription,
                                product,
                                plan,
                                entitlement,
                                did,
                                sipEndpoint,
                                channels,
                                tenantSipTrunk,
                                platformTrunk
                        )
                );

        subscription.setTenantAppId(tenantResponse.tenantAppId());
        subscriptionRepository.save(subscription);

        // 9. Clear entitlement cache
        entitlementService.invalidateCache(tenantId);

        publishActivationSuccessEvent(subscription);
        return buildResponse(
                subscription,
                did,
                tenantSipTrunk,
                channels,
                plan,
                tenantResponse
        );
    }

    private void publishActivationSuccessEvent(Subscription subscription) {

        // Replace with Kafka / Outbox
        log.info("Publishing SubscriptionActivationFailedEvent for {}",
                subscription.getId());

        productEventProducer.publishSubscriptionActivated(
                new SubscriptionActivatedEvent(
                         subscription.getId(),
                         subscription.getTenantId(),
                        "ACTIVE"
                     ));


    }

    private void publishActivationFailedEvent(Subscription subscription, Exception e) {

        // Replace with Kafka / Outbox
        log.info("Publishing SubscriptionActivationFailedEvent for {}",
                subscription.getId());

        productEventProducer.publishSubscriptionFailed(
                    new SubscriptionActivationFailedEvent(
                         subscription.getId(),
                         subscription.getTenantId(),
                         e.getMessage()
                     ));


    }

    private void safeCleanup(Subscription sub,
                             Did did,
                             SipEndpoint sipEndpoint,
                             PstnChannelBundle channels,
                             TenantSipTrunk trunk) {

        try {
            if (trunk != null) {
                tenantSipTrunkRepository.deleteById(trunk.getId());
            }
        } catch (Exception ignored) {}

        try {
            if (channels != null) {
                channelBundleRepository.deleteById(channels.getId());
            }
        } catch (Exception ignored) {}

        try {
            if (sipEndpoint != null) {
                sipEndpointService.delete(sipEndpoint.getId());
            }
        } catch (Exception ignored) {}

        try {
            if (did != null) {
                didRepository.deleteById(did.getId());
            }
        } catch (Exception ignored) {}
    }
    /**
     * Build COMPLETE subscription data for tenant-service.
     * Used to configure FreePBX, Kamailio, etc.
     */
    private TenantServiceClient.SubscriptionData buildSubscriptionData(
            Subscription subscription,
            Product product,
            Plan plan,
            PlanEntitlement entitlement,
            Did did,
            SipEndpoint sipEndpoint,
            PstnChannelBundle channels,
            TenantSipTrunk tenantSipTrunk,
            SipTrunk platformTrunk) {

        // Get product apps
        List<TenantServiceClient.ProductAppInfo> productApps = productAppRepository
                .findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(product.getId())
                .stream()
                .map(app -> new TenantServiceClient.ProductAppInfo(
                        app.getAppType().name(),
                        app.getDisplayName(),
                        app.getSubdomain(),
                        app.getIcon(),
                        app.getDisplayOrder()))
                .toList();

        return TenantServiceClient.SubscriptionData.builder()
                // Subscription
                .subscriptionId(subscription.getId())
                .tenantId(subscription.getTenantId())
                .status(subscription.getStatus().name())
                .subscribedAt(subscription.getSubscribedAt())
                .activatedAt(subscription.getActivatedAt())
                .expiresAt(subscription.getExpiresAt())
                // Product & Plan references
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .planId(plan.getId())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .planTier(plan.getTier().name())
                .monthlyPrice(plan.getMonthlyPrice())
                // Entitlements
                .agentSeats(subscription.getAgentSeats())
                .maxAgents(entitlement.getMaxAgents())
                .maxDids(entitlement.getMaxDids())
                .maxChannels(entitlement.getMaxPstnChannels())
                .includedMinutes(subscription.getIncludedMinutes())
                .aiRatePerMin(plan.getAiRatePerMin())
                // DID
                .did(TenantServiceClient.DidData.builder()
                        .id(did.getId())
                        .number(did.getNumber())
                        .displayNumber(did.getDisplayNumber())
                        .country(did.getCountry())
                        .region(did.getRegion())
                        .city(did.getCity())
                        .status(did.getStatus().name())
                        .monthlyRental(did.getMonthlyRental())
                        .build())
                // SIP Endpoint (for inbound DID registration to Kamailio)
                .sipEndpoint(TenantServiceClient.SipEndpointData.builder()
                        .id(sipEndpoint.getId())
                        .username(sipEndpoint.getUsername())
                        .passwordHash(sipEndpoint.getPasswordHash())
                        .domain(sipEndpoint.getDomain())
                        .realm(sipEndpoint.getRealm())
                        .build())
                // Channels
                .channels(TenantServiceClient.ChannelData.builder()
                        .id(channels.getId())
                        .direction(channels.getDirection().name())
                        .total(channels.getTotalChannels())
                        .inbound(channels.getInboundChannels())
                        .outbound(channels.getOutboundChannels())
                        .build())
                // Tenant SIP Trunk (customer's credentials to YOUR platform)
                .tenantSipTrunk(TenantServiceClient.TenantSipTrunkData.builder()
                        .id(tenantSipTrunk.getId())
                        .username(tenantSipTrunk.getUsername())
                        .passwordHash(tenantSipTrunk.getPasswordHash())
                        .passwordPlain(tenantSipTrunk.getPasswordPlain())
                        .domain(tenantSipTrunk.getDomain())
                        .port(tenantSipTrunk.getPort())
                        .realm(tenantSipTrunk.getRealm())
                        .transport(tenantSipTrunk.getTransport().name())
                        .maxConcurrentCalls(tenantSipTrunk.getMaxConcurrentCalls())
                        .build())
                // Platform SIP Trunk (Epsilon - for outbound calls)
                .platformTrunk(platformTrunk != null ? TenantServiceClient.PlatformTrunkData.builder()
                        .id(platformTrunk.getId())
                        .provider(platformTrunk.getProvider().name())
                        .server(platformTrunk.getServer())
                        .port(platformTrunk.getPort())
                        .transport(platformTrunk.getTransport().name())
                        .codecs(platformTrunk.getCodecs())
                        .build() : null)
                // Apps
                .productApps(productApps)
                .build();
    }

    @Transactional
    public SubscriptionResponse retryProvisioning(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (subscription.getStatus() != SubscriptionStatus.PENDING_PROVISION) {
            throw new IllegalStateException("Subscription is not in PENDING_PROVISION state");
        }

        UUID tenantId = subscription.getTenantId();
        Plan plan = subscription.getPlan();
        Product product = subscription.getProduct();
        PlanEntitlement entitlement = planEntitlementRepository.findByPlan_Id(plan.getId()).orElseThrow();

        Did did = subscription.getDidId() != null
                ? didRepository.findById(subscription.getDidId()).orElse(null)
                : null;

        SipEndpoint sipEndpoint = null;
        PstnChannelBundle channels = null;
        TenantSipTrunk tenantSipTrunk = null;

        if (!subscription.isDidProvisioned()) {
            throw new IllegalStateException("Cannot retry - DID not provisioned");
        }

        // Load or create SIP endpoint
        if (subscription.getSipEndpointId() != null) {
            sipEndpoint = sipEndpointService.getById(subscription.getSipEndpointId());
        }

        if (sipEndpoint == null && did != null) {
            // Try to find by DID
            sipEndpoint = sipEndpointService.getByDidId(did.getId());
        }
        if (sipEndpoint == null && did != null && !subscription.isSipEndpointCreated()) {
            sipEndpoint = sipEndpointService.createEndpoint(did);
            subscription.markSipEndpointCreated(sipEndpoint.getId());
            subscriptionRepository.save(subscription);
        }

        // Load or create channels
        if (subscription.getChannelBundleId() != null) {
            channels = channelBundleRepository.findById(subscription.getChannelBundleId()).orElse(null);
        } else if (!subscription.isChannelsAllocated()) {
            channels = createChannelBundle(tenantId, subscription.getId(), product.getCode(), plan, entitlement, null);
            subscription.markChannelsAllocated(channels.getId());
            subscriptionRepository.save(subscription);
        }

        // Load or create tenant SIP trunk
        if (subscription.getTenantSipTrunkId() != null) {
            tenantSipTrunk = tenantSipTrunkRepository.findById(subscription.getTenantSipTrunkId()).orElse(null);
        } else {
            TenantServiceClient.TenantInfo tenant = tenantClient.getTenant(tenantId);
            tenantSipTrunk = tenantSipTrunkService.createForSubscription(tenantId, subscription.getId(), tenant.slug());
            subscription.setTenantSipTrunkId(tenantSipTrunk.getId());
            subscriptionRepository.save(subscription);
        }

        TenantServiceClient.SubscriptionActiveResponse tenantResponse = null;

        if (subscription.isFullyProvisioned()) {
            subscription.activate();
            subscriptionRepository.save(subscription);

            SipTrunk platformTrunk = sipTrunkRepository.findPlatformTrunk().orElse(null);
            tenantResponse = tenantClient.notifySubscriptionActive(
                    buildSubscriptionData(subscription, product, plan, entitlement, did, sipEndpoint, channels, tenantSipTrunk, platformTrunk)
            );
        }

        return buildResponse(subscription, did, tenantSipTrunk, channels, plan, tenantResponse);
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

    // ==================== PRIVATE METHODS ====================

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

    private Did provisionDid(UUID tenantId, String productCode,SubscriptionRequest.DidInfo didInfo) {
        SipTrunk trunk = sipTrunkRepository.findPlatformTrunk()
                .orElseThrow(() -> new RuntimeException("Platform trunk not configured"));

        if (productRequiresOutboundTrunk(productCode)) {
            trunk = sipTrunkRepository.findPlatformTrunk()
                    .orElseThrow(() -> new RuntimeException(
                            "Platform trunk not configured — required for outbound product " + productCode));
        } else {
            log.info("Skipping outbound trunk lookup for inbound-only product {}", productCode);
        }
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
                .monthlyRental(didInfo.getMonthlyFee())
                .currency("INR") //TODO change for different countries based on DID country
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

        Integer inbound = null;
        Integer outbound = null;

        if (channelConfig != null && channelConfig.getTotalChannels() != null) {
            totalChannels = channelConfig.getTotalChannels();
        }

        switch (direction) {
            case INBOUND:
                inbound = totalChannels;
                break;
            case OUTBOUND:
                outbound = totalChannels;
                break;
            case BOTH:
                if (channelConfig != null && channelConfig.getInboundChannels() != null && channelConfig.getOutboundChannels() != null) {
                    inbound = channelConfig.getInboundChannels();
                    outbound = channelConfig.getOutboundChannels();
                } else {
                    inbound = (int) Math.ceil(totalChannels * 0.6);
                    outbound = totalChannels - inbound;
                }
                break;
        }

        SipTrunk trunk = sipTrunkRepository.findPlatformTrunk().orElse(null);

        PstnChannelBundle bundle = PstnChannelBundle.builder()
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
                .build();

        return channelBundleRepository.save(bundle);
    }

    private ChannelDirection getDirectionForProduct(String productCode) {
        return switch (productCode) {
            case "CONV_IVR" -> ChannelDirection.INBOUND;
            case "OUTBOUND_DIALER" -> ChannelDirection.OUTBOUND;
            default -> ChannelDirection.BOTH;
        };
    }

    private boolean productRequiresOutboundTrunk(String productCode) {
        return switch (productCode) {
            case "CONV_IVR", "VIRTUAL_RECEPTIONIST" -> false;
            case "AI_CC", "BASIC_PBX", "OUTBOUND_DIALER" -> true;
            default -> {
                log.warn("Unknown product code '{}', defaulting to require outbound trunk", productCode);
                yield true;
            }
        };
    }
    private PlanAssignment assignPlan(UUID tenantId, UUID subscriptionId, Plan plan, Integer agentCount) {
        // Don't deactivate existing - multiple subscriptions allowed
        PlanAssignment assignment = PlanAssignment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .plan(plan)
                .effectiveFrom(Instant.now())
                .active(true)
                .entitlementOverrides(agentCount != null ? java.util.Map.of("agentCount", agentCount) : null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return planAssignmentRepository.save(assignment);
    }

    private void createRecurringCharges(UUID tenantId, UUID subscriptionId, Plan plan, Did did, Integer agentCount) {
        billingClient.createRecurringCharge(tenantId,
                RecurringChargeRequest.platformFee(plan.getMonthlyPrice(), subscriptionId));

        if (did.getMonthlyRental() != null && did.getMonthlyRental().signum() > 0) {
            billingClient.createRecurringCharge(tenantId,
                    RecurringChargeRequest.didRental(did.getMonthlyRental(), did.getId(), did.getNumber(), subscriptionId));
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

    private SubscriptionResponse buildResponse(Subscription sub, Did did, TenantSipTrunk sipTrunk,
                                               PstnChannelBundle channels, Plan plan,
                                               TenantServiceClient.SubscriptionActiveResponse tenantResponse) {
        SubscriptionResponse.SubscriptionResponseBuilder builder = SubscriptionResponse.builder()
                .subscriptionId(sub.getId())
                .tenantAppId(sub.getTenantAppId())
                .status(sub.getStatus().name())
                .provisioningStatus(sub.isFullyProvisioned() ? "COMPLETED" : "IN_PROGRESS")
                .did(mapDid(did))
                .sipIntegration(mapSipIntegration(sipTrunk))
                .channels(mapChannels(channels))
                .plan(mapPlan(plan, sub.getExpiresAt()));

        if (tenantResponse != null) {
            builder.apps(tenantResponse.apps().stream()
                    .map(app -> AppInfo.builder()
                            .type(app.appType())
                            .displayName(app.displayName())
                            .url(app.url())
                            .icon(app.icon())
                            .build())
                    .toList());
/*
            builder.adminCredentials(AdminCredentials.builder()
                    .email(tenantResponse.adminCredentials().email())
                    .temporaryPassword(tenantResponse.adminCredentials().temporaryPassword())
                    .loginUrl(tenantResponse.adminCredentials().loginUrl())
                    .build());*/
        }

        return builder.build();
    }

    private DidDetails mapDid(SubscriptionRequest.DidInfo didInfo) {
        if (didInfo == null) return null;

        return DidDetails.builder()
                .number(didInfo.getNumber())
                .displayNumber(
                        formatDisplayNumber(didInfo.getNumber())
                )
                .country(
                        didInfo.getCountry() != null
                                ? didInfo.getCountry()
                                : "IN"
                )
                .region(didInfo.getRegion())
                .city(didInfo.getCity())
                .status("PENDING_PROVISION")
                .build();
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
        if (number != null && number.startsWith("+91") && number.length() == 13) {
            return number.substring(0, 3) + " " + number.substring(3, 8) + " " + number.substring(8);
        }
        return number;
    }
}