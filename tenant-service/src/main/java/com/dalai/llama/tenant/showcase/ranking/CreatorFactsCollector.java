package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Turns one creator's rows into the facts the ladder reads. Shared by the nightly ranking run and
 * the creator's own visibility view, so the two can never disagree about a level. */
@Component
@RequiredArgsConstructor
public class CreatorFactsCollector {

    private final ConvertedRequestCounter convertedRequests;
    private final RankingProperties ranking;
    private final ShowcaseProperties showcase;

    /** @param showable this creator's items that pass the item-and-video public rules */
    public CreatorLadder.CreatorFacts collect(CreatorPublicProfile profile, CreatorYouTubeChannel channel,
                                              List<ShowcaseItem> showable, OffsetDateTime now) {
        OffsetDateTime windowStart = now.minusDays(ranking.ladder().l3WindowDays());
        List<ShowcaseItem> funded = showable.stream()
                .filter(i -> i.getOrigin() == ShowcaseOrigin.PLATFORM && i.getFundedVerifiedAt() != null)
                .toList();
        return new CreatorLadder.CreatorFacts(
                channel != null && channel.getStatus() == ChannelStatus.VERIFIED,
                showable.size(),
                showcase.picks().minInitialPicks(),
                basicsComplete(profile, channel),
                (int) showable.stream().filter(i -> i.getOrigin() == ShowcaseOrigin.PLATFORM).count(),
                (int) funded.stream().filter(i -> i.getClientLockedAt() != null && i.getClientLockedAt().isAfter(windowStart)).count(),
                convertedRequests.convertedRequests(profile.getTenantId()),
                funded.stream().map(ShowcaseItem::getClientLockedAt).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
    }

    private static boolean basicsComplete(CreatorPublicProfile p, CreatorYouTubeChannel channel) {
        boolean avatar = p.getAvatarUrl() != null
                || (channel != null && channel.getStatus() == ChannelStatus.VERIFIED && channel.getChannelThumbnailUrl() != null);
        return p.getDisplayName() != null && avatar && p.getHeadline() != null && !p.getIndustries().isEmpty();
    }
}
