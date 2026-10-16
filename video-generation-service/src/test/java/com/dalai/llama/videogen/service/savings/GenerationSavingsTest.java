package com.dalai.llama.videogen.service.savings;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient.RateCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationSavingsTest {

    private static final String WAN = "alibaba/wan-3.0-prime";
    // The live rate card's wan tiers and the Topaz slow-down rate (V132).
    private static final List<RateCard> WAN_RATES = List.of(
            rate("480p", "0.068"), rate("720p", "0.14"), rate("1080p", "0.28"), rate(null, "0.068"));
    private static final List<RateCard> TOPAZ = List.of(new RateCard("topaz/interpolate/video", null, new BigDecimal("0.015"), "USD"));

    @Test
    void aShortLowResolutionClipSlowedToItsPlanIsComparedWithThePlanAtFullQuality() {
        // Planned 7s; generated 4s at 480p, then slowed back to 7s.
        GenerationSavings.Totals totals = GenerationSavings.of(
                List.of(job(7, 4, "480p", "GENERATED")), model -> WAN_RATES, TOPAZ);

        assertThat(totals.planned()).isEqualByComparingTo("1.96");            // 7s x 0.28 (1080p)
        assertThat(totals.made()).isEqualByComparingTo("0.377");              // 4s x 0.068 + 7s x 0.015
        assertThat(totals.plannedSeconds()).isEqualTo(7);
        assertThat(totals.generatedSeconds()).isEqualTo(4);
    }

    @Test
    void aClipMadeAsPlannedIsNotSlowed_andAnUnknownTierPricesAtTheTierlessRate() {
        GenerationSavings.Totals totals = GenerationSavings.of(
                List.of(job(4, 4, null, "GENERATED")), model -> WAN_RATES, TOPAZ);

        assertThat(totals.planned()).isEqualByComparingTo("1.12");            // 4s x 0.28
        assertThat(totals.made()).isEqualByComparingTo("0.272");              // 4s x 0.068, no slow-down
    }

    @Test
    void uploadedAndUnfinishedClipsInventNoSaving() {
        VideoGenJob pending = job(7, 4, "480p", "GENERATED");
        pending.setStatus(JobStatus.PENDING_APPROVAL);

        GenerationSavings.Totals totals = GenerationSavings.of(
                List.of(job(7, 7, "480p", "UPLOADED"), pending), model -> WAN_RATES, TOPAZ);

        assertThat(totals.shots()).isZero();
        assertThat(totals.planned()).isEqualByComparingTo("0");
        assertThat(totals.made()).isEqualByComparingTo("0");
    }

    @Test
    void slowingPaysOnlyUnderTheBreakEvenShare_pricedAtTheModelsCheapestTier() {
        List<RateCard> topazAtFal = List.of(new RateCard("topaz/interpolate/video", null, new BigDecimal("0.03"), "USD"));
        // Wan 3.0 at 480p: 1 - 0.03 / 0.068.
        assertThat(GenerationSavings.breakEven(WAN_RATES, topazAtFal)).isEqualByComparingTo("0.5588");
        // Seedance 2.0 fast: its tier-less row is the 720p price; the 480p tier is what counts.
        List<RateCard> seedance = List.of(new RateCard("s", "480p", new BigDecimal("0.1076"), "USD"),
                new RateCard("s", "720p", new BigDecimal("0.24192"), "USD"), new RateCard("s", null, new BigDecimal("0.24192"), "USD"));
        assertThat(GenerationSavings.breakEven(seedance, topazAtFal)).isEqualByComparingTo("0.7212");
        // Slowing that costs more than the model never pays; an unpriced model has no answer.
        assertThat(GenerationSavings.breakEven(List.of(new RateCard("c", null, new BigDecimal("0.02"), "USD")), topazAtFal)).isEqualByComparingTo("0");
        assertThat(GenerationSavings.breakEven(List.of(), topazAtFal)).isNull();
    }

    private static VideoGenJob job(int planned, int generated, String resolution, String origin) {
        return VideoGenJob.builder()
                .modelId(WAN).status(JobStatus.COMPLETED).outputObjectKey("clips/x.mp4").outputOrigin(origin)
                .plannedDurationSeconds(planned).durationSeconds(generated).resolution(resolution)
                .build();
    }

    private static RateCard rate(String resolution, String perSecond) {
        return new RateCard(WAN, resolution, new BigDecimal(perSecond), "USD");
    }
}
