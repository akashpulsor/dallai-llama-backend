package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/** One item's score (rule 13): quality x origin factor x creator standing. Quality uses only our
 * own data (YouTube's counts may not be used for metrics). Pure. */
@Component
@RequiredArgsConstructor
public class ShowcaseScorer {

    private final RankingProperties properties;

    public record ItemFacts(
            ShowcaseOrigin origin,
            boolean fundedVerified,
            int likes,
            int plays,
            int fullPlays,
            int inquiries,
            OffsetDateTime publishedAt
    ) {
    }

    /** Platform-wide averages the per-item rates are smoothed toward. */
    public record Priors(double likeRate, double completionRate) {
    }

    public double score(ItemFacts item, double standing, double maturity, Priors priors, OffsetDateTime now) {
        return quality(item, priors, now) * originFactor(item.origin(), maturity) * standing;
    }

    double quality(ItemFacts item, Priors priors, OffsetDateTime now) {
        RankingProperties.Scoring s = properties.scoring();
        double k = s.smoothingPlays();
        double likeRate = (item.likes() + k * priors.likeRate()) / (item.plays() + k);
        double completionRate = (item.fullPlays() + k * priors.completionRate()) / (item.plays() + k);
        double brandIntent = Math.min(1.0, item.inquiries() / (double) s.brandIntentSaturation());
        double ageDays = item.publishedAt() == null ? 0 : Math.max(0, Duration.between(item.publishedAt(), now).toHours() / 24.0);
        double freshness = Math.pow(0.5, ageDays / s.freshnessHalfLifeDays());
        double weighted = s.weightFunded() * (item.fundedVerified() ? 1 : 0)
                + s.weightLikeRate() * likeRate
                + s.weightCompletionRate() * completionRate
                + s.weightBrandIntent() * brandIntent
                + s.weightFreshness() * freshness;
        return weighted / s.weightTotal();
    }

    /** External work counts fully at launch and sinks toward {@code externalFloorFactor} as the
     * platform matures (rule 13's origin decay). */
    double originFactor(ShowcaseOrigin origin, double maturity) {
        if (origin == ShowcaseOrigin.PLATFORM) return 1.0;
        double m = Math.max(0, Math.min(1, maturity));
        return 1 - m * (1 - properties.bootstrap().externalFloorFactor());
    }
}
