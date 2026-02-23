package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.exception.EntitlementExceededException;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.dto.response.*;
import com.dalai.llama.product.repository.*;
import com.dalai.llama.product.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private final PlanAssignmentRepository assignmentRepository;
    private final PlanEntitlementRepository entitlementRepository;
    private final DidRepository didRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final PlanRepository planRepository;
    private final PlanAiConfigRepository aiConfigRepository;
    private final ProductAppRepository productAppRepository;

    private static final String CACHE_NAME = "entitlements";

    @Override
    @Cacheable(value = CACHE_NAME, key = "'tenant:' + #tenantId")
    public PlanEntitlement getEffectiveEntitlements(UUID tenantId) {
        log.debug("Fetching entitlements for tenant: {}", tenantId);

        PlanAssignment assignment = assignmentRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new PlanNotFoundException("No active plan for tenant: " + tenantId));

        return entitlementRepository.findByPlan_Id(assignment.getPlan().getId())
                .orElseThrow(() -> new PlanNotFoundException(
                        "No entitlements for plan: " + assignment.getPlan().getCode()));
    }

    @Override
    public void validateDidLimit(UUID tenantId) {
        PlanEntitlement entitlements = getEffectiveEntitlements(tenantId);

        long currentDids = didRepository.countByTenantIdAndStatusIn(
                tenantId,
                List.of(DidStatus.ACTIVE, DidStatus.PENDING, DidStatus.PROVISIONING)
        );

        if (currentDids >= entitlements.getMaxDids()) {
            throw new EntitlementExceededException(
                    "DID limit exceeded. Max: " + entitlements.getMaxDids() + ", Current: " + currentDids);
        }
    }

    /**
     * Get COMPLETE subscription configuration.
     * This is the MAIN method called by tenant-service.
     */
    @Override
    @Cacheable(value = "subscription-config", key = "#subscriptionId")
    @Transactional(readOnly = true)
    public ProductConfigResponse getSubscriptionConfig(UUID subscriptionId) {
        log.debug("Fetching complete config for subscription: {}", subscriptionId);

        // Get subscription with plan and product
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        Plan plan = subscription.getPlan();
        Product product = plan.getProduct();

        // Get entitlements
        PlanEntitlement entitlement = entitlementRepository.findByPlan_Id(plan.getId())
                .orElseThrow(() -> new RuntimeException("Entitlements not found for plan: " + plan.getCode()));

        // Get AI config (optional)
        PlanAiConfig aiConfig = aiConfigRepository.findByPlan_Id(plan.getId()).orElse(null);

        // Get product apps
        List<ProductApp> apps = productAppRepository
                .findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(product.getId());

        log.debug("Found {} apps for product {}", apps.size(), product.getCode());

        return ProductConfigResponse.builder()
                .subscriptionId(subscriptionId)
                .tenantId(subscription.getTenantId())
                .product(mapProduct(product))
                .plan(mapPlan(plan))
                .entitlements(mapEntitlement(entitlement))
                .aiConfig(mapAiConfig(aiConfig, plan))
                .apps(mapApps(apps))
                .build();
    }

    /**
     * Get entitlements for a subscription.
     */
    @Override
    @Cacheable(value = "subscription-entitlements", key = "#subscriptionId")
    @Transactional(readOnly = true)
    public PlanEntitlementResponse getEntitlementsForSubscription(UUID subscriptionId) {
        log.debug("Fetching entitlements for subscription: {}", subscriptionId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));

        return getEntitlementsForPlan(subscription.getPlan().getCode());
    }

    /**
     * Get entitlements for a plan.
     */
    @Override
    @Cacheable(value = "plan-entitlements", key = "#planCode")
    @Transactional(readOnly = true)
    public PlanEntitlementResponse getEntitlementsForPlan(String planCode) {
        log.debug("Fetching entitlements for plan: {}", planCode);

        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planCode));

        PlanEntitlement entitlement = entitlementRepository.findByPlan_Id(plan.getId())
                .orElseThrow(() -> new RuntimeException("Entitlements not found for plan: " + planCode));

        return mapEntitlement(entitlement);
    }

    /**
     * Get apps for a product.
     */
    @Override
    @Cacheable(value = "product-apps", key = "#productCode")
    @Transactional(readOnly = true)
    public ProductAppsResponse getProductApps(String productCode) {
        log.debug("Fetching apps for product: {}", productCode);

        Product product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productCode));

        List<ProductApp> apps = productAppRepository
                .findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(product.getId());

        return ProductAppsResponse.builder()
                .productCode(productCode)
                .productName(product.getName())
                .apps(mapApps(apps))
                .build();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "entitlements", key = "'tenant:' + #tenantId"),
            @CacheEvict(value = "subscription-config", allEntries = true),
            @CacheEvict(value = "subscription-entitlements", allEntries = true),
            @CacheEvict(value = "plan-entitlements", allEntries = true),
            @CacheEvict(value = "product-apps", allEntries = true)
    })
    public void invalidateCache(UUID tenantId) {
        log.info("Invalidated all relevant caches for tenant: {}", tenantId);
    }

    // ==================== MAPPERS ====================

    private ProductInfo mapProduct(Product p) {
        return ProductInfo.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .type(p.getType())
                .description(p.getDescription())
                .build();
    }

    private PlanInfo mapPlan(Plan p) {
        return PlanInfo.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .tier(p.getTier().name())
                .monthlyPrice(p.getMonthlyPrice())
                .perAgentFee(p.getPerAgentFee())
                .setupFee(p.getSetupFee())
                .includedAgents(p.getIncludedAgents())
                .includedMinutes(p.getIncludedMinutes())
                .aiStackType(p.getAiStackType() != null ? p.getAiStackType().name() : null)
                .aiRatePerMin(p.getAiRatePerMin())
                .build();
    }

    private PlanEntitlementResponse mapEntitlement(PlanEntitlement e) {
        return PlanEntitlementResponse.builder()
                // Capacity
                .maxAgents(e.getMaxAgents() != null ? e.getMaxAgents() : 0)
                .maxSupervisors(e.getMaxSupervisors() != null ? e.getMaxSupervisors() : 0)
                .maxConcurrentLogins(0) // Add to entity if needed
                .maxPstnChannels(e.getMaxPstnChannels() != null ? e.getMaxPstnChannels() : 0)
                .maxDids(e.getMaxDids() != null ? e.getMaxDids() : 0)
                .maxQueues(e.getMaxQueues() != null ? e.getMaxQueues() : 0)
                .maxIvrFlows(e.getMaxIvrFlows() != null ? e.getMaxIvrFlows() : 0)
                .maxRingGroups(e.getMaxRingGroups() != null ? e.getMaxRingGroups() : 0)
                .maxExtensions(e.getMaxExtensions() != null ? e.getMaxExtensions() : 0)

                // Direction
                .inboundEnabled(true) // Default true, add to entity if needed
                .outboundEnabled(true)

                // AI features
                .aiSttEnabled(e.isAiSttEnabled())
                .aiLlmEnabled(e.isAiLlmEnabled())
                .aiBotEnabled(e.isAiBotEnabled())
                .aiSentimentEnabled(e.isAiSentimentEnabled())
                .aiRoutingEnabled(e.isAiRoutingEnabled())
                .aiNoiseCancellationEnabled(e.isAiNoiseCancellationEnabled())
                .aiVoiceMorphEnabled(e.isAiVoiceMorphEnabled())
                .aiAgentAssistEnabled(e.isAiAgentAssistEnabled())
                .aiTokensPerMonth(e.getAiTokensPerMonth() != null ? e.getAiTokensPerMonth() : 0)

                // Call features
                .bargeEnabled(e.isBargeEnabled())
                .whisperEnabled(e.isWhisperEnabled())
                .listenEnabled(e.isListenEnabled())
                .conferenceEnabled(e.isConferenceEnabled())
                .callbackEnabled(e.isCallbackEnabled())
                .blindTransferEnabled(e.isBlindTransferEnabled())
                .attendedTransferEnabled(e.isAttendedTransferEnabled())
                .warmTransferEnabled(e.isWarmTransferEnabled())

                // Recording
                .recordingEnabled(e.isRecordingEnabled())
                .recordingStorageGb(e.getRecordingStorageGb() != null ? e.getRecordingStorageGb() : 0)
                .recordingRetentionDays(e.getRecordingRetentionDays() != null ? e.getRecordingRetentionDays() : 30)
                .screenRecordingEnabled(e.isScreenRecordingEnabled())

                // IVR
                .basicIvrEnabled(e.isBasicIvrEnabled())
                .conversationalIvrEnabled(e.isConversationalIvrEnabled())
                .ivrMultiLanguageEnabled(e.isIvrMultiLanguageEnabled())

                // Dialer
                .progressiveDialerEnabled(e.isProgressiveDialerEnabled())
                .predictiveDialerEnabled(e.isPredictiveDialerEnabled())
                .previewDialerEnabled(e.isPreviewDialerEnabled())
                .amdEnabled(e.isAmdEnabled())
                .dncManagementEnabled(e.isDncManagementEnabled())

                // Voicemail
                .voicemailEnabled(e.isVoicemailEnabled())
                .voicemailTranscriptionEnabled(e.isVoicemailTranscriptionEnabled())

                // Integrations
                .crmIntegrationEnabled(e.isCrmIntegrationEnabled())
                .screenPopEnabled(e.isScreenPopEnabled())
                .apiAccessEnabled(e.isApiAccessEnabled())
                .webhookEnabled(e.isWebhookEnabled())

                // Reporting
                .analyticsEnabled(true) // Default true
                .analyticsRetentionDays(90) // Default
                .basicReportingEnabled(e.isBasicReportingEnabled())
                .advancedReportingEnabled(e.isAdvancedReportingEnabled())
                .customReportsEnabled(e.isCustomReportsEnabled())
                .wallboardEnabled(e.isWallboardEnabled())

                // Usage limits
                .includedMinutesInbound(e.getIncludedMinutesInbound() != null ? e.getIncludedMinutesInbound() : 0)
                .includedMinutesOutbound(e.getIncludedMinutesOutbound() != null ? e.getIncludedMinutesOutbound() : 0)
                .ratePerMinuteInbound(e.getRatePerMinuteInbound() != null ? e.getRatePerMinuteInbound() : BigDecimal.ZERO)
                .ratePerMinuteOutbound(e.getRatePerMinuteOutbound() != null ? e.getRatePerMinuteOutbound() : BigDecimal.ZERO)
                .aiRatePerMinute(e.getAiRatePerMinute() != null ? e.getAiRatePerMinute() : BigDecimal.ZERO)

                // Deployment
                .dedicatedInfrastructure(e.isDedicatedInfrastructure())
                .customDomainEnabled(e.isCustomDomainEnabled())
                .slaTier(e.getSlaTier() != null ? e.getSlaTier() : "STANDARD")

                .build();
    }

    private AiConfigResponse mapAiConfig(PlanAiConfig config, Plan plan) {
        if (config == null) {
            return AiConfigResponse.builder()
                    .aiStackType(plan.getAiStackType() != null ? plan.getAiStackType().name() : null)
                    .totalAiCostPerMin(plan.getAiRatePerMin())
                    .build();
        }

        return AiConfigResponse.builder()
                .aiStackType(plan.getAiStackType() != null ? plan.getAiStackType().name() : null)
                .sttProvider(config.getSttProvider() != null ? config.getSttProvider().getProviderName() : null)
                .sttModel(config.getSttProvider() != null ? config.getSttProvider().getModelName() : null)
                .sttCostPerMin(config.getSttCostPerMin())
                .ttsProvider(config.getTtsProvider() != null ? config.getTtsProvider().getProviderName() : null)
                .ttsModel(config.getTtsProvider() != null ? config.getTtsProvider().getModelName() : null)
                .ttsCostPerMin(config.getTtsCostPerMin())
                .llmProvider(config.getLlmProvider() != null ? config.getLlmProvider().getProviderName() : null)
                .llmModel(config.getLlmProvider() != null ? config.getLlmProvider().getModelName() : null)
                .llmCostPerMin(config.getLlmCostPerMin())
                .totalAiCostPerMin(config.getTotalAiCostPerMin())
                .build();
    }

    private List<ProductAppResponse> mapApps(List<ProductApp> apps) {
        return apps.stream()
                .map(app -> ProductAppResponse.builder()
                        .id(app.getId())
                        .appType(app.getAppType().name())
                        .subdomain(app.getSubdomain())
                        .displayName(app.getDisplayName())
                        .keycloakClientSuffix(app.getKeycloakClientSuffix())
                        .frontendImage(app.getFrontendImage())
                        .frontendPort(app.getFrontendPort() != null ? app.getFrontendPort() : 80)
                        .requiredRoles(app.getRequiredRoles())
                        .icon(app.getIcon())
                        .description(app.getDescription())
                        .displayOrder(app.getDisplayOrder() != null ? app.getDisplayOrder() : 0)
                        .enabled(app.isEnabled())
                        .build())
                .toList();
    }
}