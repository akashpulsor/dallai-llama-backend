package com.dalai.llama.tenant.showcase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Every number in the reinforcement model (CREATOR_SHOWCASE.md §10 / rules 11-14), bound from
 * {@code showcase-ranking.*}. Change a value, redeploy, and call the re-score endpoint: no code or
 * migration. {@link #version} is written next to every score so a ranking can be traced back. */
@ConfigurationProperties(prefix = "showcase-ranking")
public record RankingProperties(
        String version,
        Ladder ladder,
        Bootstrap bootstrap,
        Scoring scoring,
        Reinforcement reinforcement,
        Landing landing
) {

    public record Ladder(
            int l2MinPlatformFilms,
            int l3MinFundedPlatformFilms,
            int l3WindowDays,
            int l4MinFundedPlatformFilms,
            int l4MinConvertedRequests,
            /* Keyed L1..L4; L0 is never shown. */
            Map<String, Double> levelWeights,
            int decayHalfLifeDays,
            double decayMin
    ) {
        public double weight(String level) {
            return levelWeights.getOrDefault(level, 0.0);
        }
    }

    public record Bootstrap(
            /* Funded platform films in the window at which the platform counts as mature. */
            int maturityTargetFundedFilms,
            /* What an EXTERNAL item's score is multiplied by once the platform is fully mature. */
            double externalFloorFactor,
            /* Creators at or above the floor must supply fillFactor x surface size items. */
            double fillFactor,
            int maxFloorStepPerRun
    ) {
    }

    public record Scoring(
            double weightFunded,
            double weightLikeRate,
            double weightCompletionRate,
            double weightBrandIntent,
            double weightFreshness,
            /* Plays an item needs before its own rates outweigh the platform average. */
            int smoothingPlays,
            /* Like and full-play rates assumed before the platform has any plays. */
            double priorLikeRate,
            double priorCompletionRate,
            /* Brand requests at which the brand-intent signal is full. */
            int brandIntentSaturation,
            int freshnessHalfLifeDays
    ) {
        public double weightTotal() {
            return weightFunded + weightLikeRate + weightCompletionRate + weightBrandIntent + weightFreshness;
        }
    }

    public record Reinforcement(
            int spotlightHours,
            int spotlightSlots,
            int explorationSlots,
            int explorationMaxAgeDays,
            int explorationMaxPlays
    ) {
    }

    public record Landing(
            int size,
            int minItemsToShow,
            int maxPerCreator,
            int maxPerIndustry,
            /* Public ids shown first, in this order; how ops features a film without a UI. */
            List<String> pinnedPublicIds,
            /* Discover "Top" page size the top floor is computed for. */
            int topSurfaceSize,
            /* Brands one automatic-picks run can need films for; sizes the auto floor. */
            int autoSurfaceSize
    ) {
    }
}
