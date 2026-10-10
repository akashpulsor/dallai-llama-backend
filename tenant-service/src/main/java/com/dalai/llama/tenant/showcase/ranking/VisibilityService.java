package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseRankingRun;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseRankingRunRepository;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The creator's ladder card (rule 14: the next step and what it unlocks are always visible).
 * The level is computed live from the same facts the nightly run uses; the floor is the last
 * run's. */
@Service
@RequiredArgsConstructor
public class VisibilityService {

    private final CreatorPublicProfileRepository profileRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final ShowcaseItemRepository itemRepository;
    private final ShowcaseRankingRunRepository runRepository;
    private final CreatorFactsCollector factsCollector;
    private final CreatorLadder ladder;
    private final ShowcaseProperties showcase;
    private final VideoHostProperties hostProperties;
    private final Clock clock;

    public record VisibilityView(
            CreatorLevel level,
            CreatorLevel nextLevel,
            String nextStep,
            double standingFactor,
            /* The level a creator needs for the landing page right now (it rises as the platform grows). */
            CreatorLevel landingFloor,
            boolean eligibleForLanding,
            OffsetDateTime activeSpotlightUntil,
            OffsetDateTime lastRankedAt
    ) {
    }

    public Optional<VisibilityView> mine(UUID tenantId) {
        return profileRepository.findById(tenantId).map(this::view);
    }

    private VisibilityView view(CreatorPublicProfile profile) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ShowcaseItem> showable = itemRepository
                .findAllShowable(now.minusDays(showcase.publicDataMaxAgeDays()), hostProperties.platformFilmsSelfHosted())
                .stream().filter(i -> i.getTenantId().equals(profile.getTenantId())).toList();
        CreatorLadder.CreatorFacts facts = factsCollector.collect(profile,
                channelRepository.findById(profile.getTenantId()).orElse(null), showable, now);
        CreatorLevel level = ladder.levelOf(facts);
        CreatorLadder.NextStep next = ladder.nextStep(level, facts);
        Optional<ShowcaseRankingRun> run = runRepository.findFirstByOrderByRunAtDesc();
        CreatorLevel floor = run.map(ShowcaseRankingRun::getLandingFloor).orElse(CreatorLevel.L1);
        OffsetDateTime spotlight = showable.stream().map(ShowcaseItem::getSpotlightUntil).filter(Objects::nonNull)
                .filter(until -> until.isAfter(now)).max(Comparator.naturalOrder()).orElse(null);
        return new VisibilityView(
                level,
                next.nextLevel(),
                next.message(),
                ladder.standing(level, facts.lastFundedPlatformAt(), now),
                floor,
                level != CreatorLevel.L0 && level.atLeast(floor),
                spotlight,
                run.map(ShowcaseRankingRun::getRunAt).orElse(null));
    }
}
