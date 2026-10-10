package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Shaping (rule 11): turns a creator's facts into a level, a standing factor and the next step
 * that would move them up. Pure: no database, no clock of its own. */
@Component
@RequiredArgsConstructor
public class CreatorLadder {

    private final RankingProperties properties;

    /** Facts gathered for one creator by the ranking run. */
    public record CreatorFacts(
            boolean channelVerified,
            /* Items that pass every public-visibility rule except the readiness count itself. */
            int showableItems,
            int minItems,
            boolean basicsComplete,
            int platformFilms,
            int fundedPlatformFilmsInWindow,
            int convertedRequests,
            OffsetDateTime lastFundedPlatformAt
    ) {
    }

    public record NextStep(CreatorLevel nextLevel, String message) {
    }

    public CreatorLevel levelOf(CreatorFacts f) {
        RankingProperties.Ladder rules = properties.ladder();
        if (!f.channelVerified() || f.showableItems() < f.minItems() || !f.basicsComplete()) return CreatorLevel.L0;
        if (f.fundedPlatformFilmsInWindow() >= rules.l4MinFundedPlatformFilms()
                || f.convertedRequests() >= rules.l4MinConvertedRequests()) return CreatorLevel.L4;
        if (f.fundedPlatformFilmsInWindow() >= rules.l3MinFundedPlatformFilms()) return CreatorLevel.L3;
        if (f.platformFilms() >= rules.l2MinPlatformFilms()) return CreatorLevel.L2;
        return CreatorLevel.L1;
    }

    /** Level weight times the decay (rule 13). The decay only applies to the funded levels and
     * halves every {@code decayHalfLifeDays} since the last funded film, never below {@code decayMin}. */
    public double standing(CreatorLevel level, OffsetDateTime lastFundedPlatformAt, OffsetDateTime now) {
        RankingProperties.Ladder rules = properties.ladder();
        double weight = rules.weight(level.name());
        if (!level.atLeast(CreatorLevel.L3) || lastFundedPlatformAt == null) return weight;
        double days = Math.max(0, Duration.between(lastFundedPlatformAt, now).toHours() / 24.0);
        double decay = Math.max(rules.decayMin(), Math.pow(0.5, days / rules.decayHalfLifeDays()));
        return weight * decay;
    }

    /** What the creator-facing ladder card says (rule 14: the next step is always visible). */
    public NextStep nextStep(CreatorLevel level, CreatorFacts f) {
        RankingProperties.Ladder rules = properties.ladder();
        return switch (level) {
            case L0 -> new NextStep(CreatorLevel.L1, !f.channelVerified()
                    ? "Link and verify your YouTube channel"
                    : f.showableItems() < f.minItems()
                    ? "Pick " + (f.minItems() - f.showableItems()) + " more video(s) from your channel for your profile"
                    : "Add a headline, a picture and the industries you work in");
            case L1 -> new NextStep(CreatorLevel.L2, "Publish a film you made on Dalaillama to your profile");
            case L2 -> new NextStep(CreatorLevel.L3, "Publish a film your client paid for in full on Dalaillama to reach the landing page");
            case L3 -> new NextStep(CreatorLevel.L4, "Publish " + Math.max(1, rules.l4MinFundedPlatformFilms() - f.fundedPlatformFilmsInWindow())
                    + " more funded film(s) within " + rules.l3WindowDays() + " days to become a Star creator");
            case L4 -> new NextStep(CreatorLevel.L4, "Keep publishing funded films to keep your standing");
        };
    }
}
