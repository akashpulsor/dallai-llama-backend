package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseRankingRun;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseRankingRunRepository;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The nightly ranking run (rules 11-13). Recomputes, from raw facts only: every creator's level
 * and standing, platform maturity, every showable item's score, and the floor of each surface.
 * Writes them as one run and records the run, so the landing page on any date can be explained. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRunJob {

    private final CreatorPublicProfileRepository profileRepository;
    private final ShowcaseItemRepository itemRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final ShowcaseRankingRunRepository runRepository;
    private final CreatorLadder ladder;
    private final VisibilityFloor floors;
    private final ShowcaseScorer scorer;
    private final CreatorFactsCollector factsCollector;
    private final RankingProperties ranking;
    private final ShowcaseProperties showcase;
    private final VideoHostProperties hostProperties;
    private final Clock clock;

    @Scheduled(cron = "${showcase-ranking.rescore-cron:0 30 2 * * *}")
    public void scheduled() {
        run();
    }

    @Transactional
    public ShowcaseRankingRun run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime freshAfter = now.minusDays(showcase.publicDataMaxAgeDays());

        List<CreatorPublicProfile> profiles = profileRepository.findAll().stream()
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE).toList();
        Map<UUID, CreatorYouTubeChannel> channels = channelRepository.findAll().stream()
                .collect(Collectors.toMap(CreatorYouTubeChannel::getTenantId, Function.identity()));
        Set<UUID> hiddenProfiles = profileRepository.findAll().stream()
                .filter(p -> p.getStatus() != ProfileStatus.ACTIVE).map(CreatorPublicProfile::getTenantId)
                .collect(Collectors.toSet());
        List<ShowcaseItem> showable = itemRepository.findAllShowable(freshAfter, hostProperties.platformFilmsSelfHosted())
                .stream().filter(i -> !hiddenProfiles.contains(i.getTenantId())).toList();
        Map<UUID, List<ShowcaseItem>> showableByCreator = showable.stream()
                .collect(Collectors.groupingBy(ShowcaseItem::getTenantId));

        // 1. Levels and standing.
        Map<UUID, CreatorLevel> levels = new java.util.HashMap<>();
        Map<UUID, Double> standings = new java.util.HashMap<>();
        int fundedInWindowTotal = 0;
        for (CreatorPublicProfile profile : profiles) {
            CreatorLadder.CreatorFacts facts = factsCollector.collect(profile, channels.get(profile.getTenantId()),
                    showableByCreator.getOrDefault(profile.getTenantId(), List.of()), now);
            fundedInWindowTotal += facts.fundedPlatformFilmsInWindow();
            CreatorLevel level = ladder.levelOf(facts);
            double standing = ladder.standing(level, facts.lastFundedPlatformAt(), now);
            profile.setLevel(level);
            profile.setStandingFactor(BigDecimal.valueOf(standing).setScale(4, RoundingMode.HALF_UP));
            profile.setLastFundedPlatformAt(facts.lastFundedPlatformAt());
            levels.put(profile.getTenantId(), level);
            standings.put(profile.getTenantId(), standing);
        }
        profileRepository.saveAll(profiles);

        // 2. Platform maturity and the priors the rates are smoothed toward.
        double maturity = Math.min(1.0, fundedInWindowTotal / (double) ranking.bootstrap().maturityTargetFundedFilms());
        ShowcaseScorer.Priors priors = priors(showable);

        // 3. Scores: every live item is rewritten; only showable ones get a number.
        Set<UUID> showableIds = showable.stream().map(ShowcaseItem::getId).collect(Collectors.toSet());
        Map<UUID, ShowcaseItem> showableById = showable.stream().collect(Collectors.toMap(ShowcaseItem::getId, Function.identity()));
        List<ShowcaseItem> live = itemRepository.findByStatus(ShowcaseItemStatus.LIVE);
        int scored = 0;
        for (ShowcaseItem item : live) {
            if (!showableIds.contains(item.getId()) || !levels.containsKey(item.getTenantId())) {
                item.setGlobalScore(null);
                item.setScoreVersion(null);
                continue;
            }
            ShowcaseItem facts = showableById.get(item.getId());
            double score = scorer.score(new ShowcaseScorer.ItemFacts(facts.getOrigin(), facts.getFundedVerifiedAt() != null,
                            facts.getLikeCount(), facts.getPlayCount(), facts.getFullPlayCount(), facts.getInquiryCount(),
                            facts.getPublishedAt()),
                    standings.get(item.getTenantId()), maturity, priors, now);
            item.setGlobalScore(BigDecimal.valueOf(score).setScale(6, RoundingMode.HALF_UP));
            item.setScoreVersion(ranking.version());
            scored++;
        }
        itemRepository.saveAll(live);

        // 4. Floors per surface, each bounded by the previous run's.
        Map<CreatorLevel, Integer> itemsByLevel = new EnumMap<>(CreatorLevel.class);
        for (ShowcaseItem item : showable) {
            CreatorLevel level = levels.get(item.getTenantId());
            if (level != null && level.atLeast(CreatorLevel.L1)) itemsByLevel.merge(level, 1, Integer::sum);
        }
        Optional<ShowcaseRankingRun> previous = runRepository.findFirstByOrderByRunAtDesc();
        ShowcaseRankingRun run = ShowcaseRankingRun.builder()
                .id(UUID.randomUUID())
                .runAt(now)
                .maturity(BigDecimal.valueOf(maturity).setScale(4, RoundingMode.HALF_UP))
                .landingFloor(floors.floor(itemsByLevel, ranking.landing().size(),
                        previous.map(ShowcaseRankingRun::getLandingFloor).orElse(null)))
                .topFloor(floors.floor(itemsByLevel, ranking.landing().topSurfaceSize(),
                        previous.map(ShowcaseRankingRun::getTopFloor).orElse(null)))
                .autoFloor(floors.floor(itemsByLevel, ranking.landing().autoSurfaceSize(),
                        previous.map(ShowcaseRankingRun::getAutoFloor).orElse(null)))
                .configVersion(ranking.version())
                .creatorsRanked(profiles.size())
                .itemsScored(scored)
                .build();
        runRepository.save(run);
        log.info("Ranking run: creators={} items={} maturity={} floors landing={} top={} auto={}",
                profiles.size(), scored, run.getMaturity(), run.getLandingFloor(), run.getTopFloor(), run.getAutoFloor());
        return run;
    }

    private ShowcaseScorer.Priors priors(List<ShowcaseItem> items) {
        long plays = items.stream().mapToLong(ShowcaseItem::getPlayCount).sum();
        if (plays == 0) return new ShowcaseScorer.Priors(ranking.scoring().priorLikeRate(), ranking.scoring().priorCompletionRate());
        long likes = items.stream().mapToLong(ShowcaseItem::getLikeCount).sum();
        long full = items.stream().mapToLong(ShowcaseItem::getFullPlayCount).sum();
        return new ShowcaseScorer.Priors(Math.min(1, likes / (double) plays), Math.min(1, full / (double) plays));
    }
}
