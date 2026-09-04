package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.TenantServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Prices a project by its video duration: platform cost is video generation cost (a configurable
 * flat {@code baseRatePerSecond} × requested seconds) PLUS the per-shot image, vision-analysis,
 * and critique costs, PLUS flat script/screenplay generation costs -- shot count is derived from
 * duration via {@code secondsPerShot} (the same default {@code
 * ShotListGenerationService.DEFAULT_SHOT_DURATION_SECONDS} convention pre-production-service
 * already uses when a shot has no explicit duration). Creator margin goes on top of the combined
 * platform cost -- same two-part shape (platform base + creator margin) as {@link
 * ClientReviewPaymentService}, reusing the exact same {@code Tenant.marginPercent} a creator
 * already sets for that flow rather than introducing a second, video-specific margin setting.
 * <p>
 * Every per-unit cost below is a flat {@code @Value} default, matching this class's own existing
 * {@code baseRatePerSecond} pattern -- deliberately NOT a new master-data table yet. A real
 * quality-tier system (different models/resolutions per tier, each with its own real costs) is a
 * separate, deferred design pass; when it lands, tier rows supersede these flat defaults rather
 * than this class growing a parallel mechanism.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoPricingService {

    private final TenantServiceClient tenantServiceClient;

    @Value("${billing.video-pricing.base-rate-per-second-inr:100}")
    private BigDecimal baseRatePerSecond;

    @Value("${billing.video-pricing.default-creator-margin-percent:12}")
    private BigDecimal defaultCreatorMarginPercent;

    @Value("${billing.video-pricing.currency:INR}")
    private String currency;

    /** Matches pre-production-service's own ShotListGenerationService.DEFAULT_SHOT_DURATION_SECONDS
     * -- the one place shot count already gets estimated from duration alone, before a script
     * exists. Kept in sync manually (no shared module between the two services); if that default
     * ever changes, update this alongside it. */
    @Value("${billing.video-pricing.seconds-per-shot:4}")
    private int secondsPerShot;

    /** Matches pre-production-service's ShotImageKind -- exactly 4 fixed kinds generated per shot
     * (STORYBOARD, PRODUCTION, LIGHTING, CAMERA_PLAN), not a tunable count. */
    @Value("${billing.video-pricing.images-per-shot:4}")
    private int imagesPerShot;

    /** Rough estimate, NOT measured against real LlmJob history yet: gemini-2.5-flash-image's real
     * per-token rate (0.0000003 input / 0.00003 output USD, see V26 seed) times an assumed ~300
     * input-prompt tokens + ~1290 output tokens (Google's documented image-token accounting at
     * standard resolution) -- (300*0.0000003 + 1290*0.00003) USD * 95 INR/USD (this service's own
     * conversion-rate convention) &#8776; &#8377;3.68/image. Recalibrate once real per-call token
     * counts are available from LlmJob for this model. */
    @Value("${billing.video-pricing.image-cost-inr:3.68}")
    private BigDecimal imageCostPerImageInr;

    /** Rough estimate, same caveat as imageCostPerImageInr: gemini-2.5-flash's real per-token rate
     * (0.0000003/0.0000025) times an assumed ~1800 combined input tokens (image + prompt) + ~200
     * output tokens for a per-shot vision-analysis call (ShotImageDescriptionService). */
    @Value("${billing.video-pricing.vision-analysis-cost-inr:0.10}")
    private BigDecimal visionAnalysisCostPerShotInr;

    /** Rough estimate: one mandatory pre-flight critique call per shot (confirmed bounded --
     * ShotContextAssemblyService: "a bounded one-revision gate, not a retry loop" -- never an
     * open-ended multiplier), assumed ~1500 input / ~300 output tokens on gemini-2.5-flash. */
    @Value("${billing.video-pricing.critique-cost-inr:0.11}")
    private BigDecimal critiqueCostPerShotInr;

    /** Rough estimate: one flat script-generation call per project (~2000 input / ~3000 output
     * tokens on gemini-2.5-flash), independent of shot count. */
    @Value("${billing.video-pricing.script-generation-cost-inr:0.77}")
    private BigDecimal scriptGenerationCostInr;

    /** Rough estimate: one flat screenplay-generation call per project (~3000 input / ~5000
     * output tokens on gemini-2.5-flash), independent of shot count. */
    @Value("${billing.video-pricing.screenplay-generation-cost-inr:1.27}")
    private BigDecimal screenplayGenerationCostInr;

    public Quote quote(UUID tenantId, int durationSeconds) {
        BigDecimal videoCost = baseRatePerSecond.multiply(BigDecimal.valueOf(durationSeconds));
        int shotCount = (int) Math.ceil(durationSeconds / (double) secondsPerShot);
        BigDecimal imageCost = imageCostPerImageInr.multiply(BigDecimal.valueOf((long) shotCount * imagesPerShot));
        BigDecimal visionAnalysisCost = visionAnalysisCostPerShotInr.multiply(BigDecimal.valueOf(shotCount));
        BigDecimal critiqueCost = critiqueCostPerShotInr.multiply(BigDecimal.valueOf(shotCount));
        BigDecimal platformCost = videoCost.add(imageCost).add(visionAnalysisCost).add(critiqueCost)
                .add(scriptGenerationCostInr).add(screenplayGenerationCostInr)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal marginPercent = resolveCreatorMargin(tenantId);
        BigDecimal creatorAmount = platformCost.multiply(marginPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = platformCost.add(creatorAmount).setScale(2, RoundingMode.HALF_UP);
        return new Quote(durationSeconds, shotCount, videoCost.setScale(2, RoundingMode.HALF_UP),
                imageCost.setScale(2, RoundingMode.HALF_UP), visionAnalysisCost.setScale(2, RoundingMode.HALF_UP),
                critiqueCost.setScale(2, RoundingMode.HALF_UP),
                scriptGenerationCostInr.add(screenplayGenerationCostInr).setScale(2, RoundingMode.HALF_UP),
                platformCost, marginPercent, creatorAmount, total, currency);
    }

    private BigDecimal resolveCreatorMargin(UUID tenantId) {
        try {
            TenantServiceClient.TenantInfo tenant = tenantServiceClient.getTenant(tenantId);
            if (tenant != null && tenant.marginPercent() != null) {
                return tenant.marginPercent();
            }
        } catch (Exception ex) {
            log.warn("Could not resolve creator margin for tenant {}, using default: {}", tenantId, ex.getMessage());
        }
        return defaultCreatorMarginPercent;
    }

    /** {@code platformCost} is the sum of the five cost fields before it -- broken out for
     * transparency (a creator overriding {@code quotedTotalPrice} can see what's actually driving
     * the platform's own cost), not because anything downstream reads the components separately
     * today. */
    public record Quote(
            int durationSeconds,
            int estimatedShotCount,
            BigDecimal videoCost,
            BigDecimal imageCost,
            BigDecimal visionAnalysisCost,
            BigDecimal critiqueCost,
            BigDecimal scriptAndScreenplayCost,
            BigDecimal platformCost,
            BigDecimal creatorMarginPercent,
            BigDecimal creatorAmount,
            BigDecimal totalPrice,
            String currency
    ) {}
}
