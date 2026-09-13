package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.CreatorVideoPlanEntitlement;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.domain.exception.SubscriptionNotFoundException;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlements;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlementsResponse;
import com.dalai.llama.product.repository.CreatorVideoPlanEntitlementRepository;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.repository.ProductRepository;
import com.dalai.llama.product.repository.SubscriptionRepository;
import com.dalai.llama.product.service.CreatorVideoEntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the creator-video product's entitlements. Deliberately status-driven, not a stored
 * flag: an ACTIVE subscription's plan entitlements apply, anything else (PAUSED/PAST_DUE/
 * CANCELLED/EXPIRED/no subscription at all) resolves to the product's free tier. This means
 * revoking access on pause/renewal-failure/cancel needs no extra write beyond the status change
 * itself plus a cache evict -- there's no separate "isProUser" flag anywhere that could drift from
 * the subscription's real state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorVideoEntitlementServiceImpl implements CreatorVideoEntitlementService {

    private static final String PRODUCT_CODE = "CREATOR_VIDEO";
    private static final String CACHE_BY_SUBSCRIPTION = "creator-video-entitlements";
    private static final String CACHE_BY_TENANT = "creator-video-entitlements-by-tenant";

    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final PlanRepository planRepository;
    private final CreatorVideoPlanEntitlementRepository entitlementRepository;

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_BY_SUBSCRIPTION, key = "#subscriptionId")
    public CreatorVideoEntitlementsResponse getEntitlementsBySubscription(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
        return resolve(subscription.getTenantId(), subscription);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_BY_TENANT, key = "#tenantId")
    public CreatorVideoEntitlementsResponse getEntitlementsByTenant(UUID tenantId) {
        var product = productRepository.findByCode(PRODUCT_CODE)
                .orElseThrow(() -> new ProductNotFoundException(PRODUCT_CODE));
        Subscription subscription = subscriptionRepository
                .findFirstByTenantIdAndProductId(tenantId, product.getId())
                .orElse(null);
        return resolve(tenantId, subscription);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = CACHE_BY_TENANT, key = "#tenantId"),
            @CacheEvict(value = CACHE_BY_SUBSCRIPTION, key = "#subscriptionId", condition = "#subscriptionId != null")
    })
    public void invalidateCache(UUID tenantId, UUID subscriptionId) {
        log.debug("Invalidated creator-video entitlement cache tenantId={} subscriptionId={}", tenantId, subscriptionId);
    }

    private CreatorVideoEntitlementsResponse resolve(UUID tenantId, Subscription subscription) {
        boolean paidEntitlementsApply = subscription != null && subscription.getStatus() == SubscriptionStatus.ACTIVE;
        Plan plan = paidEntitlementsApply ? subscription.getPlan() : freePlan();

        CreatorVideoEntitlements entitlements = entitlementRepository.findByPlan_Id(plan.getId())
                .map(this::toEntitlements)
                .orElseGet(CreatorVideoEntitlements::freeDefaults);

        return CreatorVideoEntitlementsResponse.builder()
                .tenantId(tenantId)
                .subscriptionId(subscription != null ? subscription.getId() : null)
                .planCode(plan.getCode())
                .planName(plan.getName())
                .status(subscription != null ? subscription.getStatus().name() : "UNSUBSCRIBED")
                .currentPeriodEnd(paidEntitlementsApply ? subscription.getExpiresAt() : null)
                .entitlements(entitlements)
                .build();
    }

    private CreatorVideoEntitlements toEntitlements(CreatorVideoPlanEntitlement e) {
        return new CreatorVideoEntitlements(
                e.isVideoCreationEnabled(),
                e.isVideoDownloadEnabled(),
                e.isEditsEnabled(),
                e.isImageUploadEnabled(),
                e.isUpscalingEnabled(),
                e.isUpscalePreviewEnabled(),
                e.isCharacterVoiceUploadEnabled(),
                e.isBriefUrlShareEnabled()
        );
    }

    private Plan freePlan() {
        return planRepository.findByProduct_CodeAndIsDefaultTrue(PRODUCT_CODE)
                .orElseThrow(() -> new PlanNotFoundException("CREATOR_VIDEO default (free) plan"));
    }
}
