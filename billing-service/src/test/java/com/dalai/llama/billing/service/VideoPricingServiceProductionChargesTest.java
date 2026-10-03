package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VideoPricingServiceProductionChargesTest {

    private final VideoPricingService pricing = VideoPricingFixture.withDefaults();

    @Test
    void cityProfessionalSplitsIntoVideoProductionAndMusic() {
        // 10s brief: platform cost 1046.83, quoted at 1299 to the client.
        ProductionCharges charges = pricing.productionCharges(quote(10, "1046.83", "1299.00"));

        assertThat(charges.scriptingAndScreenplay()).isEqualByComparingTo("2.04");
        assertThat(charges.shotPlanning()).isEqualByComparingTo("0.63");
        assertThat(charges.frameGeneration()).isEqualByComparingTo("44.16");
        assertThat(charges.videoGeneration()).isEqualByComparingTo("1000.00");
        assertThat(charges.videoProduction()).isEqualByComparingTo("1046.83");
        assertThat(charges.musicProduction()).isEqualByComparingTo("252.17");
        assertThat(charges.total()).isEqualByComparingTo("1299.00");
    }

    @Test
    void linesStillAddUpToTheSnapshotAfterARateMoves() {
        // Quoted when the platform cost was 900 for the same 10s -- today's rates price it higher.
        ProductionCharges charges = pricing.productionCharges(quote(10, "900.00", "1100.00"));

        assertThat(charges.scriptingAndScreenplay().add(charges.shotPlanning()).add(charges.frameGeneration())
                .add(charges.videoGeneration())).isEqualByComparingTo("900.00");
        assertThat(charges.musicProduction()).isEqualByComparingTo("200.00");
    }

    @Test
    void noBreakdownWithoutADurationPricedQuote() {
        assertThat(pricing.productionCharges(quote(null, null, "1299.00"))).isNull();
    }

    private static ProjectQuote quote(Integer seconds, String platformCost, String total) {
        return new ProjectQuote(seconds, platformCost == null ? null : new BigDecimal(platformCost),
                new BigDecimal(total), new BigDecimal("24"), "INR", BigDecimal.ZERO, false);
    }
}
