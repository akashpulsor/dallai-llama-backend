package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Builds the landing grid (CREATOR_SHOWCASE.md §10.6): the films ops put in the hero, pinned ids,
 * then active spotlights, then
 * exploration slots, then the rest by score from creators at or above the landing floor, then any
 * room left by score from creators below it. Caps per
 * creator and per industry apply to all of that except pins; any room still left takes the
 * remaining films past the caps, so few creators don't leave the grid half empty. Below {@code minItemsToShow} the grid is
 * empty, so the landing page hides the section rather than showing a thin one. */
@Service
@RequiredArgsConstructor
public class LandingAssembler {

    /** Candidates read per assembly; far more than the grid needs, so the caps have room. */
    private static final int CANDIDATE_LIMIT = 200;

    private final PublicShowcaseService showcase;
    private final RankingProperties ranking;
    private final Clock clock;
    private final Random random = new Random();

    public List<PublicShowcaseCard> assemble() {
        RankingProperties.Landing landing = ranking.landing();
        RankingProperties.Reinforcement reinforce = ranking.reinforcement();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Everyone public and not taken off the landing by ops, by score: exploration and pins may
        // come from below the floor.
        List<ShowcaseItem> everyone = onLanding();
        CreatorLevel floor = showcase.currentFloors().landing();
        Map<UUID, CreatorLevel> levelOf = showcase.levelsOf(everyone);

        Grid grid = new Grid(landing);
        heroes(everyone).forEach(grid::pin);
        for (String pinned : landing.pinnedPublicIds()) {
            everyone.stream().filter(i -> i.getPublicId().equals(pinned)).findFirst().ifPresent(grid::pin);
        }
        everyone.stream()
                .filter(i -> i.getSpotlightUntil() != null && i.getSpotlightUntil().isAfter(now))
                .sorted(Comparator.comparing(ShowcaseItem::getSpotlightUntil).reversed())
                .limit(reinforce.spotlightSlots())
                .forEach(grid::add);
        List<ShowcaseItem> explorers = new ArrayList<>(everyone.stream()
                .filter(i -> levelOf.getOrDefault(i.getTenantId(), CreatorLevel.L0).atLeast(CreatorLevel.L2))
                .filter(i -> i.getPublishedAt().isAfter(now.minusDays(reinforce.explorationMaxAgeDays())))
                .filter(i -> i.getPlayCount() < reinforce.explorationMaxPlays())
                .toList());
        for (int slot = 0; slot < reinforce.explorationSlots() && !explorers.isEmpty(); slot++) {
            ShowcaseItem chosen = weightedPick(explorers);
            explorers.remove(chosen);
            grid.add(chosen);
        }
        everyone.stream()
                .filter(i -> levelOf.getOrDefault(i.getTenantId(), CreatorLevel.L0).atLeast(floor))
                .forEach(grid::add);
        // Room left: creators below the floor fill it, still by score, so the landing page shows
        // creators' films before inspiration while there are few of them. The floor only orders.
        everyone.forEach(grid::add);
        // Still room: every remaining film, past the per-creator and per-industry caps. The caps keep
        // variety first; with few creators they would otherwise hide most of their work.
        everyone.forEach(grid::fill);

        List<ShowcaseItem> chosen = grid.items();
        return chosen.size() < landing.minItemsToShow() ? List.of() : showcase.toCards(chosen);
    }

    /** The films ops put in the hero, newest first. {@link #assemble()} starts with the same films,
     * so a page that shows the first one in its hero drops it from the grid. */
    public List<PublicShowcaseCard> hero() {
        return showcase.toCards(heroes(onLanding()));
    }

    private List<ShowcaseItem> onLanding() {
        return showcase.rankedPublic(EnumSet.allOf(CreatorLevel.class), null, CANDIDATE_LIMIT).stream()
                .filter(i -> !i.isLandingHidden())
                .toList();
    }

    private static List<ShowcaseItem> heroes(List<ShowcaseItem> items) {
        return items.stream()
                .filter(i -> i.getLandingHeroAt() != null)
                .sorted(Comparator.comparing(ShowcaseItem::getLandingHeroAt).reversed())
                .toList();
    }

    /** Variable reward (rule 14): a random pick, weighted by score so better new work wins more
     * often but any new platform film can win. */
    private ShowcaseItem weightedPick(List<ShowcaseItem> items) {
        double total = items.stream().mapToDouble(LandingAssembler::weight).sum();
        double target = random.nextDouble() * total;
        for (ShowcaseItem item : items) {
            target -= weight(item);
            if (target <= 0) return item;
        }
        return items.get(items.size() - 1);
    }

    private static double weight(ShowcaseItem item) {
        return item.getGlobalScore() == null ? 0.01 : Math.max(0.01, item.getGlobalScore().doubleValue());
    }

    /** The grid being filled, enforcing size and the per-creator / per-industry caps. */
    private static final class Grid {
        private final RankingProperties.Landing rules;
        private final Map<UUID, ShowcaseItem> items = new LinkedHashMap<>();
        private final Map<UUID, Integer> perCreator = new HashMap<>();
        private final Map<ShowcaseIndustry, Integer> perIndustry = new HashMap<>();

        Grid(RankingProperties.Landing rules) {
            this.rules = rules;
        }

        void pin(ShowcaseItem item) {
            if (items.size() < rules.size()) put(item);
        }

        /** Up to the grid's size, past the caps. */
        void fill(ShowcaseItem item) {
            if (items.size() < rules.size()) put(item);
        }

        void add(ShowcaseItem item) {
            if (items.size() >= rules.size() || items.containsKey(item.getId())) return;
            if (perCreator.getOrDefault(item.getTenantId(), 0) >= rules.maxPerCreator()) return;
            if (perIndustry.getOrDefault(item.getIndustry(), 0) >= rules.maxPerIndustry()) return;
            put(item);
        }

        private void put(ShowcaseItem item) {
            if (items.putIfAbsent(item.getId(), item) != null) return;
            perCreator.merge(item.getTenantId(), 1, Integer::sum);
            perIndustry.merge(item.getIndustry(), 1, Integer::sum);
        }

        List<ShowcaseItem> items() {
            return new ArrayList<>(items.values());
        }
    }
}
