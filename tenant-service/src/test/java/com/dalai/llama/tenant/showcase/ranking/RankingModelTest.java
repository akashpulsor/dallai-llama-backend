package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The ladder, the floor and the scorer on their own (CREATOR_SHOWCASE.md rules 11-13). */
class RankingModelTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-09T10:00:00Z");

    private final CreatorLadder ladder = new CreatorLadder(TestRanking.defaults());
    private final VisibilityFloor floor = new VisibilityFloor(TestRanking.defaults());
    private final ShowcaseScorer scorer = new ShowcaseScorer(TestRanking.defaults());

    @Test
    void ladderLevels() {
        assertThat(ladder.levelOf(facts(false, 2, true, 0, 0, 0))).isEqualTo(CreatorLevel.L0);
        assertThat(ladder.levelOf(facts(true, 1, true, 0, 0, 0))).isEqualTo(CreatorLevel.L0);
        assertThat(ladder.levelOf(facts(true, 2, false, 0, 0, 0))).isEqualTo(CreatorLevel.L0);
        assertThat(ladder.levelOf(facts(true, 2, true, 0, 0, 0))).isEqualTo(CreatorLevel.L1);
        assertThat(ladder.levelOf(facts(true, 2, true, 1, 0, 0))).isEqualTo(CreatorLevel.L2);
        assertThat(ladder.levelOf(facts(true, 2, true, 1, 1, 0))).isEqualTo(CreatorLevel.L3);
        assertThat(ladder.levelOf(facts(true, 4, true, 3, 3, 0))).isEqualTo(CreatorLevel.L4);
        assertThat(ladder.levelOf(facts(true, 2, true, 0, 0, 2))).as("two converted requests").isEqualTo(CreatorLevel.L4);
    }

    @Test
    void nextStepAlwaysSaysWhatToDo() {
        assertThat(ladder.nextStep(CreatorLevel.L0, facts(false, 0, false, 0, 0, 0)).message()).contains("YouTube channel");
        assertThat(ladder.nextStep(CreatorLevel.L0, facts(true, 1, false, 0, 0, 0)).message()).contains("1 more video");
        assertThat(ladder.nextStep(CreatorLevel.L2, facts(true, 2, true, 1, 0, 0)).nextLevel()).isEqualTo(CreatorLevel.L3);
    }

    @Test
    void standingDecaysGentlyForFundedLevelsOnly() {
        assertThat(ladder.standing(CreatorLevel.L1, null, NOW)).isEqualTo(0.6);
        assertThat(ladder.standing(CreatorLevel.L3, NOW, NOW)).isCloseTo(1.0, within(1e-9));
        assertThat(ladder.standing(CreatorLevel.L3, NOW.minusDays(60), NOW)).isCloseTo(0.5, within(1e-6));
        assertThat(ladder.standing(CreatorLevel.L3, NOW.minusDays(365), NOW)).as("never below decay-min").isEqualTo(0.5);
    }

    @Test
    void floorStartsAtL1AndRisesOneLevelPerRunAsFundedWorkArrives() {
        assertThat(floor.floor(levels(30, 0, 0, 0), 12, null)).isEqualTo(CreatorLevel.L1);
        // 18 items (1.5 x 12) from L3 and above: the target is L3, but one step per run.
        Map<CreatorLevel, Integer> grown = levels(30, 5, 18, 0);
        assertThat(floor.floor(grown, 12, null)).isEqualTo(CreatorLevel.L3);
        assertThat(floor.floor(grown, 12, CreatorLevel.L1)).isEqualTo(CreatorLevel.L2);
        assertThat(floor.floor(grown, 12, CreatorLevel.L2)).isEqualTo(CreatorLevel.L3);
        // Funded work drying up lowers it again, also one step at a time.
        assertThat(floor.floor(levels(30, 0, 2, 0), 12, CreatorLevel.L3)).isEqualTo(CreatorLevel.L2);
    }

    @Test
    void platformMaturityPushesExternalWorkDownButNeverToZero() {
        assertThat(scorer.originFactor(ShowcaseOrigin.EXTERNAL, 0)).isEqualTo(1.0);
        assertThat(scorer.originFactor(ShowcaseOrigin.EXTERNAL, 0.5)).isCloseTo(0.65, within(1e-9));
        assertThat(scorer.originFactor(ShowcaseOrigin.EXTERNAL, 1)).isCloseTo(0.3, within(1e-9));
        assertThat(scorer.originFactor(ShowcaseOrigin.PLATFORM, 1)).isEqualTo(1.0);
    }

    @Test
    void atColdStartFundingAndFreshnessDecide_thenEngagementTakesOver() {
        ShowcaseScorer.Priors priors = new ShowcaseScorer.Priors(0.05, 0.3);
        double funded = scorer.quality(item(true, 0, 0, 0, 0, NOW), priors, NOW);
        double unfunded = scorer.quality(item(false, 0, 0, 0, 0, NOW), priors, NOW);
        double old = scorer.quality(item(false, 0, 0, 0, 0, NOW.minusDays(60)), priors, NOW);
        assertThat(funded).isGreaterThan(unfunded);
        assertThat(unfunded).isGreaterThan(old);

        // One like on one play barely moves an item: the smoothing stops it from winning.
        double oneLike = scorer.quality(item(false, 1, 1, 1, 0, NOW), priors, NOW);
        assertThat(oneLike - unfunded).isLessThan(0.02);
        // Strong engagement plus saturated brand interest (3 requests) overtakes a fresh funded film;
        // likes and plays alone don't, by design: funding leads until brands start asking.
        assertThat(scorer.quality(item(false, 60, 200, 150, 0, NOW), priors, NOW)).isLessThan(funded);
        double loved = scorer.quality(item(false, 60, 200, 150, 3, NOW), priors, NOW);
        assertThat(loved).isGreaterThan(funded);
    }

    private static CreatorLadder.CreatorFacts facts(boolean channel, int showable, boolean basics,
                                                    int platform, int fundedInWindow, int converted) {
        return new CreatorLadder.CreatorFacts(channel, showable, 2, basics, platform, fundedInWindow, converted,
                fundedInWindow > 0 ? NOW : null);
    }

    private static ShowcaseScorer.ItemFacts item(boolean funded, int likes, int plays, int full, int inquiries,
                                                 OffsetDateTime published) {
        return new ShowcaseScorer.ItemFacts(ShowcaseOrigin.EXTERNAL, funded, likes, plays, full, inquiries, published);
    }

    private static Map<CreatorLevel, Integer> levels(int l1, int l2, int l3, int l4) {
        Map<CreatorLevel, Integer> m = new EnumMap<>(CreatorLevel.class);
        m.put(CreatorLevel.L1, l1);
        m.put(CreatorLevel.L2, l2);
        m.put(CreatorLevel.L3, l3);
        m.put(CreatorLevel.L4, l4);
        return m;
    }
}
