package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's charge-estimation and usage-markup cluster - previously
 * the plan's "VideoGenerationBillingService" row: the AI Short Starter package top-up charge,
 * per-scene/per-audio-asset customer-charge estimation, the billing summary line items shown in
 * the UI, and the usage-markup math shared by both.
 *
 * <p>Like CastCharacterResolver, needs no ScreenplayVideoService collaborators - just
 * BillingWalletService and four configured BigDecimal values, all passed directly rather than
 * reached through an owner back-reference.
 */
final class VideoGenerationBiller {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationBiller.class);

    private final BillingWalletService billingWalletService;
    private final BigDecimal aiShortStarterPriceInr;
    private final BigDecimal packageUsdInrRate;
    private final BigDecimal usageMarkupPercent;
    private final BigDecimal videoUsageMarkupPercent;

    VideoGenerationBiller(
            BillingWalletService billingWalletService,
            BigDecimal aiShortStarterPriceInr,
            BigDecimal packageUsdInrRate,
            BigDecimal usageMarkupPercent,
            BigDecimal videoUsageMarkupPercent
    ) {
        this.billingWalletService = billingWalletService;
        this.aiShortStarterPriceInr = aiShortStarterPriceInr;
        this.packageUsdInrRate = packageUsdInrRate;
        this.usageMarkupPercent = usageMarkupPercent;
        this.videoUsageMarkupPercent = videoUsageMarkupPercent;
    }

    Map<String, Object> chargeAiShortStarterPackageIfNeeded(
            CreatorScript script,
            UUID runId,
            List<Map<String, Object>> scenes,
            Map<String, Object> run
    ) {
        Map<String, Object> existingBilling = firstMap(run == null ? null : run.get("packageBilling"));
        String existingStatus = firstText(existingBilling.get("status"));
        if ("AI_SHORT_STARTER_60".equals(firstText(existingBilling.get("packageCode")))
                && ("CHARGED".equals(existingStatus) || "COVERED_BY_PROVIDER_USAGE".equals(existingStatus))) {
            return existingBilling;
        }
        BigDecimal packagePrice = positiveMoney(aiShortStarterPriceInr);
        if (packagePrice.signum() <= 0) {
            return Map.of();
        }
        UUID tenantId = uuidValue(script.getTenantId());
        if (tenantId == null) {
            log.warn("Skipping creator video package charge because tenantId is not a UUID scriptId={} runId={}",
                    script.getId(), runId);
            return Map.of();
        }
        UUID packageScopeId = script.getProjectId() == null ? runId : script.getProjectId();

        BigDecimal priorVideoChargeInr = estimatePriorPackageIncludedCustomerChargeInr(scenes, run).setScale(2, RoundingMode.HALF_UP);
        BigDecimal topUpInr = packagePrice.subtract(priorVideoChargeInr).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("packageCode", "AI_SHORT_STARTER_60");
        billing.put("packageName", "End-to-end 60-second AI video");
        billing.put("packageScopeId", packageScopeId);
        billing.put("packagePriceInr", packagePrice.setScale(2, RoundingMode.HALF_UP));
        billing.put("estimatedPriorVideoCustomerChargeInr", priorVideoChargeInr);
        billing.put("estimatedIncludedCustomerChargeInr", priorVideoChargeInr);
        billing.put("topUpAmountInr", topUpInr);
        billing.put("currency", "INR");
        billing.put("pricingPolicy", "end_to_end_60_second_video_with_two_client_reviews");
        billing.put("usageMarkupPercent", positiveMoney(videoUsageMarkupPercent));
        billing.put("videoUsageMarkupPercent", positiveMoney(videoUsageMarkupPercent));
        billing.put("generalUsageMarkupPercent", positiveMoney(usageMarkupPercent));
        billing.put("basicEditingIncluded", true);
        billing.put("includedClientReviewRounds", 2);
        billing.put("includedWorkflow", List.of(
                "IDEA",
                "PLANNING",
                "STORYBOARD",
                "FIRST_TWO_CLIENT_REVIEWS",
                "VOICE_CLONING",
                "AVATAR_CREATION",
                "FAL_VIDEO",
                "FINAL_VIDEO_PRODUCTION"
        ));
        billing.put("usdInrRate", positiveMoney(packageUsdInrRate));

        // Ask billing to settle the full package price. Its package-scope cap subtracts
        // every already-recorded included usage event atomically, so Kafka ordering cannot
        // make the wallet total exceed or fall short of the configured package price.
        String idempotencyKey = "CREATOR_VIDEO_PACKAGE:" + packageScopeId + ":AI_SHORT_STARTER_60";
        billingWalletService.recordCreatorPackageUsage(
                tenantId,
                packageScopeId,
                packagePrice,
                "INR",
                "End-to-end 60-second AI video package settlement, including two client reviews",
                idempotencyKey
        );
        billing.put("status", "CHARGED");
        billing.put("requestedSettlementAmountInr", packagePrice.setScale(2, RoundingMode.HALF_UP));
        billing.put("walletDebitPolicy", "CAP_AT_PACKAGE_PRICE_AFTER_INCLUDED_USAGE");
        billing.put("idempotencyKey", idempotencyKey);
        billing.put("sourceType", "CREATOR_VIDEO_PACKAGE");
        billing.put("chargedAt", OffsetDateTime.now().toString());
        return billing;
    }

    private BigDecimal estimatePriorVideoSceneCustomerChargeInr(List<Map<String, Object>> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> scene : scenes) {
            Map<String, Object> providerMetadata = firstMap(scene.get("providerMetadata"));
            Map<String, Object> costMetadata = firstMap(scene.get("costMetadata"), providerMetadata.get("costMetadata"));
            if (costMetadata.isEmpty() || Boolean.FALSE.equals(booleanValue(costMetadata.get("modelApiInteracted"), true))) {
                continue;
            }
            BigDecimal customerCost = firstBigDecimal(costMetadata.get("customerTotalCost"), costMetadata.get("billableTotalCost"));
            BigDecimal actualCost = firstBigDecimal(
                    costMetadata.get("actualTotalCost"),
                    costMetadata.get("totalCost"),
                    costMetadata.get("providerCost"),
                    costMetadata.get("legacyBillingCost")
            );
            BigDecimal minimumCustomerCost = applyVideoUsageMarkup(actualCost);
            if (customerCost == null || customerCost.signum() <= 0
                    || (minimumCustomerCost.signum() > 0 && customerCost.compareTo(minimumCustomerCost) < 0)) {
                customerCost = minimumCustomerCost;
            }
            total = total.add(convertPackageCostToInr(customerCost, firstText(costMetadata.get("currency"), "INR")));
        }
        return total.max(BigDecimal.ZERO);
    }

    private BigDecimal estimatePriorPackageIncludedCustomerChargeInr(
            List<Map<String, Object>> scenes,
            Map<String, Object> run
    ) {
        BigDecimal total = estimatePriorVideoSceneCustomerChargeInr(scenes);
        for (Map<String, Object> audioAsset : List.of(
                firstMap(run == null ? null : run.get("dialogueAudio")),
                firstMap(run == null ? null : run.get("backgroundMusic"))
        )) {
            if (audioAsset.isEmpty()) {
                continue;
            }
            Map<String, Object> providerDetails = firstMap(audioAsset.get("providerDetails"));
            Map<String, Object> costMetadata = firstMap(
                    audioAsset.get("costMetadata"),
                    providerDetails.get("costMetadata")
            );
            if (costMetadata.isEmpty() || Boolean.FALSE.equals(booleanValue(costMetadata.get("modelApiInteracted"), true))) {
                continue;
            }
            BigDecimal customerCost = firstBigDecimal(costMetadata.get("customerTotalCost"), costMetadata.get("billableTotalCost"));
            BigDecimal actualCost = firstBigDecimal(
                    costMetadata.get("actualTotalCost"),
                    costMetadata.get("totalCost"),
                    costMetadata.get("providerCost"),
                    costMetadata.get("legacyBillingCost")
            );
            BigDecimal minimumCustomerCost = applyUsageMarkup(actualCost);
            if (customerCost == null || customerCost.signum() <= 0
                    || (minimumCustomerCost.signum() > 0 && customerCost.compareTo(minimumCustomerCost) < 0)) {
                customerCost = minimumCustomerCost;
            }
            total = total.add(convertPackageCostToInr(customerCost, firstText(costMetadata.get("currency"), "INR")));
        }
        return total.max(BigDecimal.ZERO);
    }

    /**
     * Keep the persisted run self-explanatory for the UI and support team. Provider usage is
     * recorded in its native currency, while the wallet is charged in INR for this package.
     */
    void refreshBillingSummary(Map<String, Object> run) {
        if (run == null) {
            return;
        }
        List<Map<String, Object>> lineItems = new ArrayList<>();
        BigDecimal providerCostInr = BigDecimal.ZERO;
        BigDecimal usageChargeInr = BigDecimal.ZERO;

        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> scene = scenes.get(index);
            Map<String, Object> providerMetadata = firstMap(scene.get("providerMetadata"));
            Map<String, Object> costMetadata = firstMap(scene.get("costMetadata"), providerMetadata.get("costMetadata"));
            Map<String, Object> line = billingUsageLine(
                    "VIDEO_SCENE",
                    "Scene " + firstText(scene.get("sceneNumber"), String.valueOf(index + 1)),
                    costMetadata,
                    positiveMoney(videoUsageMarkupPercent)
            );
            if (!line.isEmpty()) {
                lineItems.add(line);
                providerCostInr = providerCostInr.add(firstBigDecimal(line.get("providerCostInr")));
                usageChargeInr = usageChargeInr.add(firstBigDecimal(line.get("walletChargeInr")));
            }
        }

        Map<String, Object> dialogueAudio = firstMap(run.get("dialogueAudio"));
        Map<String, Object> dialogueLine = billingUsageLine(
                "DIALOGUE_VOICE",
                "Dialogue voiceover",
                audioCostMetadata(dialogueAudio),
                positiveMoney(usageMarkupPercent)
        );
        if (!dialogueLine.isEmpty()) {
            lineItems.add(dialogueLine);
            providerCostInr = providerCostInr.add(firstBigDecimal(dialogueLine.get("providerCostInr")));
            usageChargeInr = usageChargeInr.add(firstBigDecimal(dialogueLine.get("walletChargeInr")));
        }

        Map<String, Object> backgroundMusic = firstMap(run.get("backgroundMusic"));
        Map<String, Object> musicLine = billingUsageLine(
                "BACKGROUND_MUSIC",
                "AI background music",
                audioCostMetadata(backgroundMusic),
                positiveMoney(usageMarkupPercent)
        );
        if (!musicLine.isEmpty()) {
            lineItems.add(musicLine);
            providerCostInr = providerCostInr.add(firstBigDecimal(musicLine.get("providerCostInr")));
            usageChargeInr = usageChargeInr.add(firstBigDecimal(musicLine.get("walletChargeInr")));
        }

        Map<String, Object> packageBilling = firstMap(run.get("packageBilling"));
        BigDecimal packageTopUpInr = firstBigDecimal(packageBilling.get("chargedAmountInr"), packageBilling.get("topUpAmountInr"));
        if (packageTopUpInr == null) {
            packageTopUpInr = BigDecimal.ZERO;
        }
        if (!packageBilling.isEmpty()) {
            Map<String, Object> packageLine = new LinkedHashMap<>();
            packageLine.put("type", "VIDEO_PACKAGE_TOP_UP");
            packageLine.put("label", "AI Short Starter package floor top-up");
            packageLine.put("providerCostInr", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            packageLine.put("platformMarginInr", packageTopUpInr.setScale(2, RoundingMode.HALF_UP));
            packageLine.put("walletChargeInr", packageTopUpInr.setScale(2, RoundingMode.HALF_UP));
            packageLine.put("status", firstText(packageBilling.get("status"), "PENDING"));
            packageLine.put("packagePriceInr", firstBigDecimal(packageBilling.get("packagePriceInr"), aiShortStarterPriceInr));
            packageLine.put("chargedAt", packageBilling.get("chargedAt"));
            lineItems.add(packageLine);
        }

        BigDecimal totalWalletChargeInr = usageChargeInr.add(packageTopUpInr).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("currency", "INR");
        summary.put("usdInrRate", positiveMoney(packageUsdInrRate));
        summary.put("videoMarginPercent", positiveMoney(videoUsageMarkupPercent));
        summary.put("generalMarginPercent", positiveMoney(usageMarkupPercent));
        summary.put("newFullVideoPackageInr", positiveMoney(aiShortStarterPriceInr).setScale(2, RoundingMode.HALF_UP));
        summary.put("providerCostInr", providerCostInr.setScale(2, RoundingMode.HALF_UP));
        summary.put("usageChargeInr", usageChargeInr.setScale(2, RoundingMode.HALF_UP));
        summary.put("packageTopUpInr", packageTopUpInr.setScale(2, RoundingMode.HALF_UP));
        summary.put("platformMarginInr", totalWalletChargeInr.subtract(providerCostInr).setScale(2, RoundingMode.HALF_UP));
        summary.put("totalWalletChargeInr", totalWalletChargeInr);
        summary.put("basicEditingIncluded", true);
        summary.put("fullRerunRequiresBillingConsent", true);
        summary.put("sceneRerunRequiresBillingConsent", true);
        summary.put("lineItems", lineItems);
        summary.put("updatedAt", OffsetDateTime.now().toString());
        run.put("billingSummary", summary);
    }

    private Map<String, Object> audioCostMetadata(Map<String, Object> audioAsset) {
        if (audioAsset == null || audioAsset.isEmpty()) {
            return Map.of();
        }
        return firstMap(
                audioAsset.get("costMetadata"),
                firstMap(audioAsset.get("providerDetails")).get("costMetadata")
        );
    }

    private Map<String, Object> billingUsageLine(
            String type,
            String label,
            Map<String, Object> costMetadata,
            BigDecimal fallbackMarkupPercent
    ) {
        if (costMetadata == null || costMetadata.isEmpty()
                || Boolean.FALSE.equals(booleanValue(costMetadata.get("modelApiInteracted"), true))) {
            return Map.of();
        }
        String currency = firstText(costMetadata.get("currency"), "INR");
        BigDecimal providerCost = firstBigDecimal(
                costMetadata.get("actualTotalCost"),
                costMetadata.get("totalCost"),
                costMetadata.get("providerCost"),
                costMetadata.get("legacyBillingCost")
        );
        BigDecimal customerCharge = firstBigDecimal(costMetadata.get("customerTotalCost"), costMetadata.get("billableTotalCost"));
        if (providerCost == null) {
            providerCost = BigDecimal.ZERO;
        }
        if (customerCharge == null) {
            customerCharge = BigDecimal.ZERO;
        }
        BigDecimal markup = firstBigDecimal(costMetadata.get("billingMarkupPercent"), fallbackMarkupPercent, BigDecimal.ZERO);
        if (providerCost.signum() > 0) {
            BigDecimal minimumCharge = providerCost.multiply(BigDecimal.ONE.add(markup.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
            if (customerCharge.signum() <= 0 || customerCharge.compareTo(minimumCharge) < 0) {
                customerCharge = minimumCharge;
            }
        }
        BigDecimal providerCostInr = convertPackageCostToInr(providerCost, currency).setScale(2, RoundingMode.HALF_UP);
        BigDecimal walletChargeInr = convertPackageCostToInr(customerCharge, currency).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", type);
        line.put("label", label);
        line.put("provider", firstText(costMetadata.get("provider")));
        line.put("model", firstText(costMetadata.get("model")));
        line.put("providerCostInr", providerCostInr);
        line.put("platformMarginInr", walletChargeInr.subtract(providerCostInr).setScale(2, RoundingMode.HALF_UP));
        line.put("walletChargeInr", walletChargeInr);
        line.put("marginPercent", markup);
        line.put("usageSource", firstText(costMetadata.get("usageSource"), costMetadata.get("source")));
        line.put("estimated", booleanValue(costMetadata.get("estimated"), false));
        return line;
    }

    private BigDecimal applyUsageMarkup(BigDecimal actualCost) {
        if (actualCost == null || actualCost.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.ONE.add(
                positiveMoney(usageMarkupPercent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
        );
        return actualCost.multiply(multiplier);
    }

    private BigDecimal applyVideoUsageMarkup(BigDecimal actualCost) {
        if (actualCost == null || actualCost.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.ONE.add(
                positiveMoney(videoUsageMarkupPercent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
        );
        return actualCost.multiply(multiplier);
    }

    private BigDecimal convertPackageCostToInr(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        String normalized = defaultString(currency, "INR").trim().toUpperCase(Locale.ROOT);
        if ("USD".equals(normalized)) {
            return amount.multiply(positiveMoney(packageUsdInrRate));
        }
        return amount;
    }
}
