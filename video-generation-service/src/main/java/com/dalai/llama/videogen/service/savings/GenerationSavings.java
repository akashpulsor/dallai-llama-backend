package com.dalai.llama.videogen.service.savings;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient.RateCard;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a film's shots would have cost generated as planned against what they cost as made, raw in
 * the provider's currency.
 *
 * <p>Planned: every shot at its planned length, at its model's top resolution. Made: the clip as
 * generated -- often shorter and at a lower resolution -- plus, where it was shorter than planned,
 * a Topaz slow-down back up to the planned length. The slow-down is counted as if AI frames were
 * always used, so the saving shown is never larger than the real one.
 *
 * <p>Only clips the platform generated count: an uploaded clip cost nothing to make, and planning
 * one would invent a saving.
 */
public final class GenerationSavings {

    private GenerationSavings() {
    }

    public record Totals(int shots, int plannedSeconds, int generatedSeconds, BigDecimal planned, BigDecimal made) {
    }

    /**
     * @param jobs     the job each shot is on
     * @param ratesFor a model's current rates
     * @param slowDown the slow-down model's rates
     */
    public static Totals of(List<VideoGenJob> jobs, Function<String, List<RateCard>> ratesFor, List<RateCard> slowDown) {
        BigDecimal slowDownRate = rateAt(slowDown, null);
        int shots = 0;
        int plannedSeconds = 0;
        int generatedSeconds = 0;
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal made = BigDecimal.ZERO;
        for (VideoGenJob job : jobs) {
            if (!generated(job)) continue;
            List<RateCard> rates = ratesFor.apply(job.getModelId());
            int generatedLength = job.getDurationSeconds();
            int plannedLength = Objects.requireNonNullElse(job.getPlannedDurationSeconds(), generatedLength);
            shots++;
            plannedSeconds += plannedLength;
            generatedSeconds += generatedLength;
            planned = planned.add(topRate(rates).multiply(BigDecimal.valueOf(plannedLength)));
            made = made.add(rateAt(rates, job.getResolution()).multiply(BigDecimal.valueOf(generatedLength)));
            if (plannedLength > generatedLength) {
                made = made.add(slowDownRate.multiply(BigDecimal.valueOf(plannedLength)));
            }
        }
        return new Totals(shots, plannedSeconds, generatedSeconds, planned, made);
    }

    /**
     * The share of a shot's planned length under which generating a shorter clip and slowing it
     * back costs less than generating the whole shot: generating g seconds and slowing to p costs
     * g x model + p x slowDown, which beats p x model only while g / p < 1 - slowDown / model.
     * Priced at the model's cheapest tier, the strictest threshold: a clip under it saves at any
     * resolution. (Not the tier-less rate -- Seedance's is its 720p price.) Zero when slowing never
     * pays; null when the model has no per-second rate.
     */
    public static BigDecimal breakEven(List<RateCard> model, List<RateCard> slowDown) {
        BigDecimal modelRate = model.stream().map(RateCard::perSecondCost).filter(Objects::nonNull)
                .filter(rate -> rate.signum() > 0).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if (modelRate.signum() <= 0) return null;
        BigDecimal ratio = BigDecimal.ONE.subtract(rateAt(slowDown, null).divide(modelRate, 4, java.math.RoundingMode.HALF_UP));
        return ratio.max(BigDecimal.ZERO);
    }

    private static boolean generated(VideoGenJob job) {
        return job.getStatus() == JobStatus.COMPLETED && job.getOutputObjectKey() != null
                && "GENERATED".equals(job.getOutputOrigin()) && job.getDurationSeconds() != null
                && job.getModelId() != null;
    }

    /** The model's dearest tier: what full quality costs. */
    static BigDecimal topRate(List<RateCard> rates) {
        return rates.stream().map(RateCard::perSecondCost).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    /** The rate a clip was billed at -- llm-gateway's own rule: the exact tier, else the tier-less
     * rate, else the dearest tier (an unpriced tier must never look cheap). */
    static BigDecimal rateAt(List<RateCard> rates, String resolution) {
        if (resolution != null) {
            for (RateCard rate : rates) {
                if (resolution.equalsIgnoreCase(rate.resolution()) && rate.perSecondCost() != null) return rate.perSecondCost();
            }
        }
        for (RateCard rate : rates) {
            if (rate.resolution() == null && rate.perSecondCost() != null) return rate.perSecondCost();
        }
        return topRate(rates);
    }
}
