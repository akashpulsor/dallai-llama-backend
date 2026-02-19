package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.client.BillingServiceClient.RecurringChargeRequest;
import com.dalai.llama.product.client.TenantServiceClient;
import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.domain.entity.enums.ChannelDirection;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.dto.request.SubscriptionRequest;
import com.dalai.llama.product.dto.response.SubscriptionResponse;
import com.dalai.llama.product.dto.response.SubscriptionResponse.*;
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

    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        UUID tenantId = request.getTenantId();
        log.info("Processing subscription for tenant {} - product: {}, plan: {}, did: {}",
                tenantId, request.getProductCode(), request.getPlanCode(), request.getDid().getNumber());

        // 1. Get Product & Plan
        Product product = productRepository.findByCode(request.getProductCode())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductCode()));

        Plan plan = planRepository.findByCode(request.getPlanCode())
                .orElseThrow(() -> new PlanNotFoundException(request.getPlanCode()));

        if (!plan.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException("Plan " + request.getPlanCode() + " does not belong to product " + request.getProductCode());
        }

        // 2. Check if this DID is already subscribed
        String didNumber = request.getDid().getNumber();
        didRepository.findByNumber(didNumber).ifPresent(existingDid -> {
            subscriptionRepository.findByDidId(existingDid.getId()).ifPresent(existingSub -> {
                if (existingSub.getStatus() == SubscriptionStatus.ACTIVE) {
                    throw new IllegalStateException("DID " + didNumber + " is already in use by another subscription");
                }
            });
        });

        // 3. Get entitlements
        PlanEntitlement entitlement = planEntitlementRepository.findByPlan_Id(plan.getId())
                .orElseThrow(() -> new RuntimeException("Plan entitlements not found"));

        // 4. Create new Subscription
        Subscription subscription = Subscription.builder()
                .tenantId(tenantId)
                .product(product)
                .plan(plan)
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .agentSeats(request.getAgentCount() != null ? request.getAgentCount() : plan.getIncludedAgents())
                .includedMinutes(plan.getIncludedMinutes() != null ? plan.getIncludedMinutes() : 0)
                .build();

        subscription = subscriptionRepository.save(subscription);

        // 5. Calculate total amount
        BigDecimal totalAmount = calculateTotal(plan, request);
        log.debug("Total subscription amount: ₹{}", totalAmount);

        // 6. Validate tenant has sufficient balance
        if (!billingClient.hasSufficientBalance(tenantId, totalAmount)) {
            log.info("Insufficient balance for tenant {}. Required: ₹{}", tenantId, totalAmount);
            return SubscriptionResponse.builder()
                    .subscriptionId(subscription.getId())
                    .status(SubscriptionStatus.PENDING_PAYMENT.name())
                    .provisioningStatus("AWAITING_PAYMENT")
                    .plan(mapPlan(plan, null))
                    .build();
        }

        // 7. Charge wallet FIRST (before provisioning)
        billingClient.chargeSubscription(tenantId, subscription.getId(), totalAmount, plan.getCode(), didNumber);

        // 8. Move to PENDING_PROVISION
        subscription.markPendingProvision();
        subscriptionRepository.save(subscription);

        // 9. Provision resources
        Did did = null;
        SipEndpoint sipEndpoint = null;
        PstnChannelBundle channels = null;
        TenantSipTrunk tenantSipTrunk = null;

        try {
            // 9a. Provision DID
            did = provisionDid(tenantId, request.getDid());
            subscription.markDidProvisioned(did.getId());
            subscriptionRepository.save(subscription);

            // 9b. Create SIP Endpoint (for DID inbound registration)
            sipEndpoint = sipEndpointService.createEndpoint(did);
            subscription.markSipEndpointCreated(sipEndpoint.getId());
            subscriptionRepository.save(subscription);

            // 9c. Create Channel Bundle (direction based on product)
            channels = createChannelBundle(tenantId, subscription.getId(), product.getCode(), plan, entitlement, request.getChannelConfig());
            subscription.markChannelsAllocated(channels.getId());
            subscriptionRepository.save(subscription);

            // 9d. Create Tenant SIP Trunk (customer's credentials to YOUR platform)
            TenantServiceClient.TenantInfo tenant = tenantClient.getTenant(tenantId);
            tenantSipTrunk = tenantSipTrunkService.createForSubscription(tenantId, subscription.getId(), tenant.slug());
            subscription.setTenantSipTrunkId(tenantSipTrunk.getId());
            subscriptionRepository.save(subscription);

        } catch (Exception e) {
            log.error("Provisioning failed for subscription {}: {}", subscription.getId(), e.getMessage(), e);
            return SubscriptionResponse.builder()
                    .subscriptionId(subscription.getId())
                    .status(SubscriptionStatus.PENDING_PROVISION.name())
                    .provisioningStatus("PARTIAL_FAILURE")
                    .did(did != null ? mapDid(did) : null)
                    .plan(mapPlan(plan, null))
                    .build();
        }

        // 10. Assign plan (for entitlement lookups)
        PlanAssignment assignment = assignPlan(tenantId, subscription.getId(), plan, request.getAgentCount());
        subscription.setPlanAssignmentId(assignment.getId());

        // 11. Activate subscription
        subscription.activate();
        subscriptionRepository.save(subscription);

        // 12. Create recurring charges for next month
        createRecurringCharges(tenantId, subscription.getId(), plan, did, request.getAgentCount());

        // 13. Get platform SIP trunk
        SipTrunk platformTrunk = sipTrunkRepository.findPlatformTrunk().orElse(null);

        // 14. Notify Tenant Service with COMPLETE subscription data
        TenantServiceClient.SubscriptionActiveResponse tenantResponse = tenantClient.notifySubscriptionActive(
                buildSubscriptionData(subscription, product, plan, entitlement, did, sipEndpoint, channels, tenantSipTrunk, platformTrunk)
        );

        // 15. Invalidate entitlement cache
        entitlementService.invalidateCache(tenantId);

        log.info("Subscription {} activated for tenant {} - product: {}, plan: {}, did: {}",
                subscription.getId(), tenantId, product.getCode(), plan.getCode(), didNumber);

        // 16. Build response
        return buildResponse(subscription, did, tenantSipTrunk, channels, plan, tenantResponse);
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
                // Product & Plan
                .productCode(product.getCode())
                .productName(product.getName())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .planTier(plan.getTier().name())
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

    private Did provisionDid(UUID tenantId, SubscriptionRequest.DidInfo didInfo) {
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
                .monthlyRental(didInfo.getMonthlyFee())
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

            builder.adminCredentials(AdminCredentials.builder()
                    .email(tenantResponse.adminCredentials().email())
                    .temporaryPassword(tenantResponse.adminCredentials().temporaryPassword())
                    .loginUrl(tenantResponse.adminCredentials().loginUrl())
                    .build());
        }

        return builder.build();
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