package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Characterization tests for the charge-estimation and usage-markup cluster moved out of
 * ScreenplayVideoService into VideoGenerationBiller - previously 0 test coverage despite being
 * real money-handling logic.
 */
class VideoGenerationBillerTest {

    @Test
    void applyUsageMarkup_addsConfiguredPercentAndFloorsAtZeroForNonPositiveCost() throws Exception {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20));
        Method method = VideoGenerationBiller.class.getDeclaredMethod("applyUsageMarkup", BigDecimal.class);
        method.setAccessible(true);

        BigDecimal result = (BigDecimal) method.invoke(biller, BigDecimal.valueOf(10));
        assertEquals(0, BigDecimal.valueOf(18.5).compareTo(result));

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) method.invoke(biller, BigDecimal.ZERO)));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) method.invoke(biller, (Object) null)));
    }

    @Test
    void applyVideoUsageMarkup_usesTheVideoSpecificPercentNotTheGeneralOne() throws Exception {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20));
        Method method = VideoGenerationBiller.class.getDeclaredMethod("applyVideoUsageMarkup", BigDecimal.class);
        method.setAccessible(true);

        BigDecimal result = (BigDecimal) method.invoke(biller, BigDecimal.valueOf(10));
        assertEquals(0, BigDecimal.valueOf(12).compareTo(result));
    }

    @Test
    void convertPackageCostToInr_convertsUsdButLeavesInrUnchanged() throws Exception {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20), BigDecimal.valueOf(90));
        Method method = VideoGenerationBiller.class.getDeclaredMethod("convertPackageCostToInr", BigDecimal.class, String.class);
        method.setAccessible(true);

        BigDecimal usdResult = (BigDecimal) method.invoke(biller, BigDecimal.valueOf(2), "USD");
        assertEquals(0, BigDecimal.valueOf(180).compareTo(usdResult));

        BigDecimal inrResult = (BigDecimal) method.invoke(biller, BigDecimal.valueOf(150), "INR");
        assertEquals(0, BigDecimal.valueOf(150).compareTo(inrResult));

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) method.invoke(biller, BigDecimal.ZERO, "USD")));
    }

    @Test
    void billingUsageLine_flooredAtMinimumChargeWhenRecordedCustomerChargeIsBelowMarkupFloor() throws Exception {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20));
        Method method = VideoGenerationBiller.class.getDeclaredMethod(
                "billingUsageLine", String.class, String.class, Map.class, BigDecimal.class
        );
        method.setAccessible(true);

        // Recorded customer charge (5) is below the 20%-marked-up floor on a $10 provider cost (12) -
        // the floor must win, protecting margin against an under-recorded billing event.
        Map<String, Object> costMetadata = Map.of(
                "modelApiInteracted", true,
                "actualTotalCost", BigDecimal.valueOf(10),
                "customerTotalCost", BigDecimal.valueOf(5),
                "currency", "INR",
                "provider", "fal.ai",
                "model", "seedance"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) method.invoke(
                biller, "VIDEO_SCENE", "Scene 1", costMetadata, BigDecimal.valueOf(20)
        );

        assertEquals(0, BigDecimal.valueOf(10).setScale(2).compareTo((BigDecimal) line.get("providerCostInr")));
        assertEquals(0, BigDecimal.valueOf(12).setScale(2).compareTo((BigDecimal) line.get("walletChargeInr")));
    }

    @Test
    void billingUsageLine_returnsEmptyWhenNoModelApiWasInteractedWith() throws Exception {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20));
        Method method = VideoGenerationBiller.class.getDeclaredMethod(
                "billingUsageLine", String.class, String.class, Map.class, BigDecimal.class
        );
        method.setAccessible(true);

        Map<String, Object> costMetadata = Map.of("modelApiInteracted", false, "actualTotalCost", BigDecimal.TEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) method.invoke(
                biller, "VIDEO_SCENE", "Scene 1", costMetadata, BigDecimal.valueOf(20)
        );
        assertTrue(line.isEmpty());
    }

    @Test
    void chargeAiShortStarterPackageIfNeeded_settlesFullPackagePriceWithStablePerScopeIdempotencyKey() throws Exception {
        BillingWalletService walletService = mock(BillingWalletService.class);
        VideoGenerationBiller biller = new VideoGenerationBiller(
                walletService,
                BigDecimal.valueOf(5999),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(85),
                BigDecimal.valueOf(20)
        );
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        CreatorScript script = CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId.toString())
                .userId("user")
                .projectId(projectId)
                .title("Script")
                .scriptPayload(new LinkedHashMap<>())
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        Map<String, Object> billing = biller.chargeAiShortStarterPackageIfNeeded(script, runId, List.of(), Map.of());

        assertEquals("CHARGED", billing.get("status"));
        assertEquals("CREATOR_VIDEO_PACKAGE:" + projectId + ":AI_SHORT_STARTER_60", billing.get("idempotencyKey"));
        verify(walletService).recordCreatorPackageUsage(
                eq(tenantId), eq(projectId), eq(BigDecimal.valueOf(5999)), eq("INR"), any(), eq(billing.get("idempotencyKey").toString())
        );
    }

    @Test
    void chargeAiShortStarterPackageIfNeeded_returnsExistingBillingWhenAlreadyCharged() {
        VideoGenerationBiller biller = biller(BigDecimal.valueOf(85), BigDecimal.valueOf(20));
        Map<String, Object> existing = Map.of("packageCode", "AI_SHORT_STARTER_60", "status", "CHARGED");
        Map<String, Object> run = Map.of("packageBilling", existing);

        Map<String, Object> result = biller.chargeAiShortStarterPackageIfNeeded(
                CreatorScript.builder().build(), UUID.randomUUID(), List.of(), run
        );

        assertEquals(existing, result);
    }

    private VideoGenerationBiller biller(BigDecimal usageMarkupPercent, BigDecimal videoUsageMarkupPercent) {
        return biller(usageMarkupPercent, videoUsageMarkupPercent, BigDecimal.valueOf(90));
    }

    private VideoGenerationBiller biller(BigDecimal usageMarkupPercent, BigDecimal videoUsageMarkupPercent, BigDecimal usdInrRate) {
        return new VideoGenerationBiller(
                mock(BillingWalletService.class),
                BigDecimal.valueOf(5999),
                usdInrRate,
                usageMarkupPercent,
                videoUsageMarkupPercent
        );
    }
}
