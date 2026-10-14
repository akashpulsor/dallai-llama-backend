package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.PublicCreatorProfileView;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.dto.ShowcaseFeedPage;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseRankingRunRepository;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.YouTubeLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Everything the public (no login) sees: a creator's page and the showcase feed. Which items
 * qualify is decided in one place, {@link ShowcaseItemRepository#PUBLIC_VISIBLE}. The feed is
 * newest-first until the ranking slice replaces the order with the score. */
@Service
@RequiredArgsConstructor
public class PublicShowcaseService {

    public static final int PAGE_SIZE = 24;

    private final CreatorPublicProfileRepository profileRepository;
    private final ShowcaseItemRepository itemRepository;
    private final YouTubeVideoRepository videoRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final ShowcaseProperties properties;
    private final VideoHostProperties hostProperties;
    private final PreProductionShowcaseClient preProduction;
    private final ShowcaseRankingRunRepository runRepository;
    private final com.dalai.llama.tenant.showcase.repository.CreatorHandleHistoryRepository handleHistoryRepository;
    private final com.dalai.llama.tenant.repository.TenantRepository tenantRepository;
    private final Clock clock;

    /** Empty (404) for unknown handles and for suspended or hidden profiles. */
    public Optional<PublicCreatorProfileView> profile(String handle) {
        return profileRepository.findByHandle(handle)
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE)
                .map(this::toProfileView);
    }

    /** Discover's tabs: NEW is newest first for everyone public; TOP is by score, limited to
     * creators at or above the top floor (rule 12); ALL is by score for everyone public. */
    public enum FeedTab { NEW, TOP, ALL }

    /** The current handle for a handle given up within the redirect window, if any. */
    public Optional<String> movedHandle(String oldHandle) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(properties.profile().oldHandleRedirectDays());
        return handleHistoryRepository.findById(oldHandle)
                .filter(h -> h.getReleasedAt().isAfter(cutoff))
                .flatMap(h -> profileRepository.findById(h.getTenantId()))
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE)
                .map(CreatorPublicProfile::getHandle);
    }

    public ShowcaseFeedPage feed(FeedTab tab, ShowcaseIndustry industry, ShowcaseFormat format, int page) {
        int safePage = Math.max(page, 0);
        PageRequest pageRequest = PageRequest.of(safePage, PAGE_SIZE + 1);
        boolean self = hostProperties.platformFilmsSelfHosted();
        List<ShowcaseItem> rows = switch (tab == null ? FeedTab.NEW : tab) {
            case NEW -> itemRepository.findPublicFeed(industry, format, freshAfter(), minItems(), self, pageRequest);
            case TOP -> topOrEveryone(industry, format, self, safePage, pageRequest);
            case ALL -> itemRepository.findPublicByScore(industry, format, freshAfter(), minItems(), self,
                    EnumSet.allOf(CreatorLevel.class), pageRequest);
        };
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ShowcaseItem> pageRows = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        return new ShowcaseFeedPage(toCards(pageRows), safePage, hasMore);
    }

    private PublicCreatorProfileView toProfileView(CreatorPublicProfile p) {
        List<ShowcaseItem> items = itemRepository.findPublicForCreator(p.getTenantId(), freshAfter(), minItems(),
                hostProperties.platformFilmsSelfHosted());
        CreatorYouTubeChannel channel = channelRepository.findById(p.getTenantId())
                .filter(c -> c.getStatus() == ChannelStatus.VERIFIED)
                .orElse(null);
        return new PublicCreatorProfileView(
                p.getHandle(),
                p.getDisplayName(),
                // The account's company, fixed with the account -- not a profile field.
                tenantRepository.findById(p.getTenantId()).map(com.dalai.llama.tenant.domain.entity.Tenant::getName).orElse(null),
                p.getHeadline(),
                p.getBio(),
                CreatorAvatar.of(p, channel),
                p.getCountryCode(),
                p.getWebsiteUrl(),
                p.getIndustries().isEmpty() ? EnumSet.noneOf(ShowcaseIndustry.class) : EnumSet.copyOf(p.getIndustries()),
                channel == null ? null : YouTubeLinks.channel(channel.getChannelId()),
                p.getFollowerCount(),
                !items.isEmpty(),
                toCards(items));
    }

    /** Loads the videos, profiles and channels behind a page of items in three queries. */
    /** Top is the creators at the top floor and above -- but while there are only a few of them
     * (bootstrapping: new creators start at L0 until the nightly run), a first page that can't fill
     * shows everyone's public films, still by score, instead of an empty tab. */
    private List<ShowcaseItem> topOrEveryone(ShowcaseIndustry industry, ShowcaseFormat format, boolean self, int page,
                                             PageRequest pageRequest) {
        List<ShowcaseItem> top = itemRepository.findPublicByScore(industry, format, freshAfter(), minItems(), self,
                levelsFrom(currentFloors().top()), pageRequest);
        if (page > 0 || top.size() >= PAGE_SIZE) return top;
        return itemRepository.findPublicByScore(industry, format, freshAfter(), minItems(), self, EnumSet.allOf(CreatorLevel.class), pageRequest);
    }

    /** The floors the last ranking run chose; L1 (everyone ready) before any run has happened. */
    Floors currentFloors() {
        return runRepository.findFirstByOrderByRunAtDesc()
                .map(r -> new Floors(r.getLandingFloor(), r.getTopFloor(), r.getAutoFloor()))
                .orElse(new Floors(CreatorLevel.L1, CreatorLevel.L1, CreatorLevel.L1));
    }

    record Floors(CreatorLevel landing, CreatorLevel top, CreatorLevel auto) {
    }

    static Set<CreatorLevel> levelsFrom(CreatorLevel floor) {
        return EnumSet.range(floor, CreatorLevel.L4);
    }

    /** Ranked public candidates for the landing grid and automatic picks. */
    List<ShowcaseItem> rankedPublic(Set<CreatorLevel> levels, ShowcaseIndustry industry, int limit) {
        return itemRepository.findPublicByScore(industry, null, freshAfter(), minItems(),
                hostProperties.platformFilmsSelfHosted(), levels, PageRequest.of(0, limit));
    }

    /** The current level of each item's creator, in one query. */
    Map<UUID, CreatorLevel> levelsOf(List<ShowcaseItem> items) {
        return profileRepository.findAllById(items.stream().map(ShowcaseItem::getTenantId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreatorPublicProfile::getTenantId, CreatorPublicProfile::getLevel));
    }

    List<PublicShowcaseCard> toCards(List<ShowcaseItem> items) {
        if (items.isEmpty()) return List.of();
        List<UUID> tenantIds = items.stream().map(ShowcaseItem::getTenantId).distinct().toList();
        Map<String, YouTubeVideo> videos = videoRepository.findAllById(items.stream().map(ShowcaseItem::getYoutubeVideoId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(YouTubeVideo::getVideoId, Function.identity()));
        Map<UUID, CreatorPublicProfile> profiles = profileRepository.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(CreatorPublicProfile::getTenantId, Function.identity()));
        Map<UUID, CreatorYouTubeChannel> channels = channelRepository.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(CreatorYouTubeChannel::getTenantId, Function.identity()));
        return items.stream()
                .map(i -> toCard(i, videos.get(i.getYoutubeVideoId()), profiles.get(i.getTenantId()), channels.get(i.getTenantId())))
                .toList();
    }

    private PublicShowcaseCard toCard(ShowcaseItem i, YouTubeVideo v, CreatorPublicProfile p, CreatorYouTubeChannel channel) {
        VideoShape shape = VideoShape.of(v, i);
        java.math.BigDecimal duration = v != null ? v.getDurationSeconds() : i.getFilmDurationSeconds();
        return new PublicShowcaseCard(
                i.getPublicId(),
                i.getYoutubeVideoId(),
                i.getTitleOverride() != null ? i.getTitleOverride() : v == null ? null : v.getTitle(),
                thumbnail(i, v),
                shape.width(),
                shape.height(),
                shape.vertical(),
                duration,
                i.getOrigin(),
                i.getIndustry(),
                i.getFormat(),
                i.getClientLabel(),
                YouTubeLinks.watch(i.getYoutubeVideoId(), shape.vertical(), duration),
                hostFor(i),
                i.getLikeCount(),
                new PublicShowcaseCard.Creator(p.getHandle(), p.getDisplayName(), CreatorAvatar.of(p, channel),
                        properties.publicBaseUrl() + "/c/" + p.getHandle()));
    }

    @org.springframework.beans.factory.annotation.Value("${showcase.public-api-base-url:https://api.dalaillama.in/api/v1/public}")
    private String publicApiBaseUrl;

    /** YouTube's thumbnail, except for a platform film that is private on YouTube (YouTube shows
     * nobody its picture): that one's poster is its own first frame, served by {@link #poster}. */
    private String thumbnail(ShowcaseItem i, YouTubeVideo v) {
        // A film made on Dalaillama shows the frame its creator chose (else its first shot's), whatever
        // YouTube has -- the profile plays our own copy of it, and the creator picks how it looks.
        return i.getOrigin() == ShowcaseOrigin.PLATFORM
                ? publicApiBaseUrl + "/showcase/" + i.getPublicId() + "/poster"
                : v.getThumbnailUrl();
    }

    /** A fresh link to a platform film's poster (its first shot's frame). */
    public Optional<String> poster(String publicId) {
        return itemRepository.findByPublicId(publicId)
                .filter(i -> i.getOrigin() == ShowcaseOrigin.PLATFORM && i.getSourceProjectId() != null && !i.isHiddenByOps())
                .map(i -> preProduction.source(i.getTenantId(), i.getSourceProjectId()).posterUrl());
    }

    /** A short-lived link to our own copy of a platform film -- the newest finished film of its
     * project, stamped -- for our own player. Empty unless the item is a publicly visible platform film. */
    public Optional<SelfSourceView> selfSource(String publicId) {
        return itemRepository.findByPublicId(publicId)
                .filter(i -> i.getOrigin() == ShowcaseOrigin.PLATFORM && i.getSourceProjectId() != null)
                .filter(i -> itemRepository.findPublicForCreator(i.getTenantId(), freshAfter(), minItems(), true).stream()
                        .anyMatch(visible -> visible.getId().equals(i.getId())))
                .map(i -> preProduction.source(i.getTenantId(), i.getSourceProjectId()))
                // The stamped copy (with the Dalaillama mark) once post-production has made it.
                .map(s -> s.stampedDownloadUrl() != null ? s.stampedDownloadUrl() : s.downloadUrl())
                .map(SelfSourceView::new);
    }

    public record SelfSourceView(String url) {
    }

    /** A film made on Dalaillama always plays from our own copy in our own player -- it may not be on
     * YouTube at all (published straight to the profile). Only a pick from the creator's channel
     * plays through YouTube. */
    private static VideoHostType hostFor(ShowcaseItem item) {
        return item.getOrigin() == ShowcaseOrigin.PLATFORM ? VideoHostType.SELF : VideoHostType.YOUTUBE;
    }

    private OffsetDateTime freshAfter() {
        return OffsetDateTime.now(clock).minusDays(properties.publicDataMaxAgeDays());
    }

    private long minItems() {
        return properties.picks().minInitialPicks();
    }
}
