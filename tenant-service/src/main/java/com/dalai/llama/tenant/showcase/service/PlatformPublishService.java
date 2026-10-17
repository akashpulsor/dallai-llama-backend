package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.PlatformProof;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoChannel;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.OfficialUploadJob;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.LinkPlatformFilmRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.OfficialUploadRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.OfficialUploadView;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.YouTubeKitView;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.OfficialUploadJobRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient.VideoInfo;
import com.dalai.llama.tenant.youtube.client.YouTubeVideoUrl;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.YouTubeVideoCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Publishing a film made on Dalaillama to YouTube, by either path in CREATOR_SHOWCASE.md §9.3:
 * the creator uploads it themselves with our kit and pastes the link back (LINK_MATCH), or we upload
 * it to Dalaillama's own channel (UPLOADED, via {@link OfficialUploadWorker}). Either way the item is
 * PLATFORM and carries the project's funding and consent facts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPublishService {

    static final String DISCLOSURE_STEP =
            "In YouTube Studio, under Details > Altered content, choose Yes: this film was made with AI.";

    private final PreProductionShowcaseClient preProduction;
    private final FundingVerifier fundingVerifier;
    private final OfficialChannelAccess officialChannel;
    private final ShowcaseItemRepository itemRepository;
    private final OfficialUploadJobRepository uploadJobRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final CreatorProfileService profileService;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache videoCache;
    private final YouTubeVideoRepository videoRepository;
    private final PublicIdGenerator publicIds;
    private final ShowcasePosterLinks posterLinks;
    private final ShowcaseProperties properties;
    private final VideoHostProperties hostProperties;
    private final RankingProperties ranking;
    private final Clock clock;

    public YouTubeKitView kit(UUID tenantId, UUID projectId) {
        ShowcaseSource source = preProduction.source(tenantId, projectId);
        CreatorPublicProfile profile = profile(tenantId);
        boolean funded = fundingVerifier.verified(source);
        boolean consent = source.marketingTermsAcceptedAt() != null;
        return new YouTubeKitView(
                source.projectName(),
                source.filmReady(),
                source.downloadUrl(),
                defaultTitle(profile, null),
                markerLine(profile) + "\n\n",
                tags(null),
                DISCLOSURE_STEP,
                funded,
                consent,
                officialBlockReason(source, funded, consent).isEmpty(),
                officialBlockReason(source, funded, consent).orElse(null));
    }

    /** Path 2: the creator uploaded the film and pasted the link. It must match the project's film
     * on every rule in §9.4, or nothing is created and the failing rule is named. */
    @Transactional
    public MyShowcaseItemView link(UUID tenantId, UUID projectId, LinkPlatformFilmRequest request) {
        CreatorYouTubeChannel channel = channelRepository.findById(tenantId)
                .filter(c -> c.getStatus() == ChannelStatus.VERIFIED)
                .orElseThrow(() -> new IllegalStateException("Verify your YouTube channel first"));
        ensureNotPublished(projectId);
        ShowcaseSource source = preProduction.source(tenantId, projectId);
        if (!source.filmReady()) throw new IllegalStateException("Publish the finished film in the project first");

        String videoId = YouTubeVideoUrl.videoId(request.url())
                .orElseThrow(() -> new IllegalArgumentException("That doesn't look like a YouTube video link"));
        VideoInfo video = youTube.videos(List.of(videoId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("We can't see that video on YouTube; make sure it is public"));
        mismatch(video, channel, source).ifPresent(reason -> {
            throw new IllegalArgumentException("This video doesn't match the project's film: " + reason);
        });

        OffsetDateTime now = OffsetDateTime.now(clock);
        videoCache.store(List.of(videoId), List.of(video), now);
        ShowcaseItem item = itemRepository.findByYoutubeVideoId(videoId).orElse(null);
        if (item != null && !item.getTenantId().equals(tenantId)) {
            throw new IllegalStateException("This video is already on another profile");
        }
        item = platformItem(item, tenantId, source, videoId, request.industry(), request.format(), request.clientLabel(),
                PlatformProof.LINK_MATCH, VideoChannel.CREATOR, now);
        ShowcaseItem saved = itemRepository.save(item);
        return ShowcasePickService.toView(saved, videoRepository.findById(videoId).orElse(null), posterLinks.of(saved));
    }

    /**
     * Path 3: the creator published the film to their own channel from Dalai Llama ("Publish to my
     * profile"): we uploaded it, so it goes on their profile as a film made on Dalaillama, with the
     * project's funding and consent facts. Nothing to do if the project is already on the profile
     * with this upload.
     *
     * <p>A project has one item, so its existing row is reused whatever its state: one removed from
     * the profile comes back, and a re-rendered film uploaded again moves the item to the newest
     * video. Looking the row up by the new video instead found nothing and inserted a second item for
     * the project, which showcase_item_project_uk refused on every retry -- the film never showed.
     */
    @Transactional
    public Optional<ShowcaseItem> recordCreatorUpload(UUID tenantId, UUID projectId, String videoId, String privacy) {
        Optional<ShowcaseItem> forProject = itemRepository.findBySourceProjectId(projectId);
        if (forProject.filter(i -> i.getStatus() != ShowcaseItemStatus.REMOVED && videoId.equals(i.getYoutubeVideoId())).isPresent()) {
            return Optional.empty();
        }
        CreatorPublicProfile profile = profile(tenantId);
        ShowcaseSource source = preProduction.source(tenantId, projectId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VideoInfo> listed = youTube.videos(List.of(videoId));
        if (!listed.isEmpty()) {
            videoCache.store(List.of(videoId), listed, now);
        } else if (videoRepository.findById(videoId).isEmpty()) {
            // YouTube may still be processing a fresh upload; the film's own facts stand in until the refresh.
            String channelId = channelRepository.findById(tenantId).map(CreatorYouTubeChannel::getChannelId).orElse("CREATOR");
            // YouTube's standard thumbnail address for the video, so the card has a picture before the refresh.
            videoRepository.save(YouTubeVideo.builder().videoId(videoId).channelId(channelId).title(source.projectName())
                    .thumbnailUrl("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg")
                    .durationSeconds(source.durationSeconds() == null ? java.math.BigDecimal.ZERO : source.durationSeconds())
                    .aspectW(source.width()).aspectH(source.height()).privacyStatus(privacy == null ? "private" : privacy.toLowerCase(java.util.Locale.ROOT))
                    .embeddable(true).ageRestricted(false).madeForKids(false).publishedAt(now).fetchedAt(now).build());
        }
        ShowcaseItem byVideo = itemRepository.findByYoutubeVideoId(videoId).orElse(null);
        if (byVideo != null && !byVideo.getTenantId().equals(tenantId)) return Optional.empty();
        ShowcaseItem existing = forProject.orElse(byVideo);
        boolean vertical = source.width() != null && source.height() != null && source.height() > source.width();
        ShowcaseItem item = platformItem(existing, tenantId, source, videoId,
                profile.getIndustries().stream().findFirst().orElse(ShowcaseIndustry.OTHER),
                vertical ? ShowcaseFormat.SOCIAL_SHORT : ShowcaseFormat.BRAND_FILM, null, PlatformProof.UPLOADED, VideoChannel.CREATOR, now);
        return Optional.of(itemRepository.save(item));
    }

    /**
     * Puts a finished film on the creator's profile, with no YouTube upload: it plays from our
     * stamped copy, its poster is its first frame. Sharing it to YouTube later fills in the video on
     * this same item. Publishing again brings back a film taken off the profile.
     */
    @Transactional
    public MyShowcaseItemView publishToProfile(UUID tenantId, UUID projectId) {
        ShowcaseSource source = preProduction.source(tenantId, projectId);
        if (!source.filmReady()) throw new IllegalStateException("Publish the finished film to the review page first");
        CreatorPublicProfile profile = profile(tenantId);
        ShowcaseItem existing = itemRepository.findBySourceProjectId(projectId).orElse(null);
        if (existing != null && !existing.getTenantId().equals(tenantId)) {
            throw new IllegalStateException("This film is already on another profile");
        }
        boolean vertical = source.width() != null && source.height() != null && source.height() > source.width();
        ShowcaseItem item = platformItem(existing, tenantId, source, existing == null ? null : existing.getYoutubeVideoId(),
                existing != null ? existing.getIndustry() : profile.getIndustries().stream().findFirst().orElse(ShowcaseIndustry.OTHER),
                existing != null ? existing.getFormat() : vertical ? ShowcaseFormat.SOCIAL_SHORT : ShowcaseFormat.BRAND_FILM,
                existing == null ? null : existing.getClientLabel(),
                existing == null || existing.getPlatformProof() == null ? PlatformProof.RENDERED : existing.getPlatformProof(),
                existing == null ? null : existing.getChannel(), OffsetDateTime.now(clock));
        if (item.getTitleOverride() == null) item.setTitleOverride(source.projectName());
        ShowcaseItem saved = itemRepository.save(item);
        return ShowcasePickService.toView(saved, saved.getYoutubeVideoId() == null ? null
                : videoRepository.findById(saved.getYoutubeVideoId()).orElse(null), posterLinks.of(saved));
    }

    /** Path 1: queue an upload to Dalaillama's channel. Re-requesting after a failure re-queues it. */
    @Transactional
    public OfficialUploadView requestOfficialUpload(UUID tenantId, UUID projectId, OfficialUploadRequest request) {
        if (!officialChannel.ready()) {
            throw new IllegalStateException("Publishing on Dalaillama's channel isn't available yet");
        }
        ensureNotPublished(projectId);
        ShowcaseSource source = preProduction.source(tenantId, projectId);
        officialBlockReason(source, fundingVerifier.verified(source), source.marketingTermsAcceptedAt() != null)
                .ifPresent(reason -> {
                    throw new IllegalStateException(reason);
                });
        OfficialUploadJob job = uploadJobRepository.findByProjectId(projectId).orElse(null);
        if (job != null && (job.getStatus() == OfficialUploadStatus.QUEUED || job.getStatus() == OfficialUploadStatus.UPLOADING)) {
            return toView(job);
        }
        if (job == null) {
            job = OfficialUploadJob.builder().id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).build();
        }
        job.setIndustry(request.industry());
        job.setFormat(request.format());
        job.setClientLabel(blankToNull(request.clientLabel()));
        job.setStatus(OfficialUploadStatus.QUEUED);
        job.setAttempts(0);
        job.setError(null);
        return toView(uploadJobRepository.save(job));
    }

    /**
     * Queues the Dalaillama-channel upload on the creator's behalf (automatic; the creator only ever
     * uploads to their own channel). Same rules as {@link #requestOfficialUpload}: Dalaillama's channel
     * connected, film published, fully paid and the client's marketing consent. False when it isn't
     * eligible or a job already exists for the film.
     */
    @Transactional
    public boolean autoQueueOfficialUpload(CreatorPublicProfile profile, UUID projectId) {
        if (uploadJobRepository.findByProjectId(projectId).isPresent()) return false;
        ShowcaseSource source = preProduction.source(profile.getTenantId(), projectId);
        if (officialBlockReason(source, fundingVerifier.verified(source), source.marketingTermsAcceptedAt() != null).isPresent()) return false;
        boolean vertical = source.width() != null && source.height() != null && source.height() > source.width();
        uploadJobRepository.save(OfficialUploadJob.builder().id(UUID.randomUUID()).tenantId(profile.getTenantId()).projectId(projectId)
                .industry(profile.getIndustries().stream().findFirst().orElse(ShowcaseIndustry.OTHER))
                .format(vertical ? ShowcaseFormat.SOCIAL_SHORT : ShowcaseFormat.BRAND_FILM)
                .status(OfficialUploadStatus.QUEUED).attempts(0).build());
        return true;
    }

    public Optional<OfficialUploadView> officialUpload(UUID tenantId, UUID projectId) {
        return uploadJobRepository.findByProjectId(projectId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .map(PlatformPublishService::toView);
    }

    /** Called by the worker once YouTube accepted the upload. */
    @Transactional
    public ShowcaseItem completeOfficialUpload(OfficialUploadJob job, ShowcaseSource source, String videoId) {
        Optional<ShowcaseItem> shown = itemRepository.findBySourceProjectId(job.getProjectId())
                .filter(i -> i.getStatus() != ShowcaseItemStatus.REMOVED && !videoId.equals(i.getYoutubeVideoId()));
        if (shown.isPresent()) return shown.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        ShowcaseItem item = platformItem(itemRepository.findByYoutubeVideoId(videoId).orElse(null), job.getTenantId(), source,
                videoId, job.getIndustry(), job.getFormat(), job.getClientLabel(), PlatformProof.UPLOADED, VideoChannel.OFFICIAL, now);
        return itemRepository.save(item);
    }

    String officialTitle(CreatorPublicProfile profile, String clientLabel) {
        return defaultTitle(profile, clientLabel);
    }

    String officialDescription(CreatorPublicProfile profile) {
        return hostProperties.officialChannel().descriptionTemplate()
                .replace("{creatorName}", profile.getDisplayName())
                .replace("{profileUrl}", profileUrl(profile));
    }

    List<String> tags(ShowcaseIndustry industry) {
        return industry == null ? List.of("dalaillama", "ai video")
                : List.of("dalaillama", "ai video", industry.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private ShowcaseItem platformItem(ShowcaseItem existing, UUID tenantId, ShowcaseSource source, String videoId,
                                      ShowcaseIndustry industry, ShowcaseFormat format, String clientLabel,
                                      PlatformProof proof, VideoChannel channel, OffsetDateTime now) {
        ShowcaseItem item = existing != null ? existing : ShowcaseItem.builder()
                .id(UUID.randomUUID())
                .publicId(publicIds.next())
                .tenantId(tenantId)
                .youtubeVideoId(videoId)
                .publishedAt(now)
                .build();
        // A reused item follows the upload it is being recorded for.
        item.setYoutubeVideoId(videoId);
        // The film's own facts, for its card whether or not it is on YouTube.
        item.setFilmDurationSeconds(source.durationSeconds());
        item.setFilmWidth(source.width());
        item.setFilmHeight(source.height());
        item.setOrigin(ShowcaseOrigin.PLATFORM);
        item.setPlatformProof(proof);
        item.setChannel(channel);
        item.setSourceProjectId(source.projectId());
        item.setClientLockedAt(source.clientLockedAt());
        boolean funded = fundingVerifier.verified(source);
        item.setFundedVerifiedAt(funded ? now : null);
        if (funded && item.getSpotlightUntil() == null) {
            item.setSpotlightUntil(now.plusHours(ranking.reinforcement().spotlightHours()));
        }
        item.setMarketingConsentAt(source.marketingTermsAcceptedAt());
        item.setIndustry(industry);
        item.setFormat(format);
        item.setClientLabel(blankToNull(clientLabel));
        item.setRightsConfirmedAt(now);
        item.setStatus(ShowcaseItemStatus.LIVE);
        return item;
    }

    /** Every rule in §9.4 for a link pasted by the creator; empty when the video matches. */
    Optional<String> mismatch(VideoInfo video, CreatorYouTubeChannel channel, ShowcaseSource source) {
        if (!video.channelId().equals(channel.getChannelId())) return Optional.of("it isn't on your linked channel");
        if (source.renderedAt() != null && video.publishedAt() != null
                && video.publishedAt().isBefore(source.renderedAt().toInstant())) {
            return Optional.of("it was published before the film was made");
        }
        if (source.durationSeconds() == null || video.duration() == null) return Optional.of("its length can't be compared");
        double difference = Math.abs(video.duration().toMillis() / 1000.0 - source.durationSeconds().doubleValue());
        if (difference > properties.platformMatch().durationToleranceSeconds()) {
            return Optional.of("its length differs from the film by %.1f seconds".formatted(difference));
        }
        String marker = properties.platformMatch().markerPrefix().toLowerCase(Locale.ROOT);
        if (video.description() == null || !video.description().toLowerCase(Locale.ROOT).contains(marker)) {
            return Optional.of("its description doesn't include the \"" + properties.platformMatch().markerPrefix() + "\" line");
        }
        return Optional.empty();
    }

    private Optional<String> officialBlockReason(ShowcaseSource source, boolean funded, boolean consent) {
        if (!officialChannel.ready()) return Optional.of("Dalaillama's channel isn't connected yet");
        if (!source.filmReady()) return Optional.of("Publish the finished film in the project first");
        if (!hostProperties.officialChannel().requireFundedAndConsent()) return Optional.empty();
        if (!funded) return Optional.of("Only films the client paid for in full, with their review on Dalaillama, go on our channel");
        if (!consent) return Optional.of("The client hasn't agreed to marketing use for this film");
        return Optional.empty();
    }

    private void ensureNotPublished(UUID projectId) {
        itemRepository.findBySourceProjectId(projectId)
                .filter(i -> i.getStatus() != ShowcaseItemStatus.REMOVED)
                .ifPresent(i -> {
                    throw new IllegalStateException("This film is already on your profile");
                });
    }

    private String defaultTitle(CreatorPublicProfile profile, String clientLabel) {
        String title = (clientLabel == null || clientLabel.isBlank() ? "" : clientLabel.trim() + " · ")
                + "Made by " + profile.getDisplayName() + " on Dalaillama";
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    private String markerLine(CreatorPublicProfile profile) {
        return properties.platformMatch().markerPrefix() + " · " + profileUrl(profile);
    }

    private String profileUrl(CreatorPublicProfile profile) {
        return properties.publicBaseUrl() + "/c/" + profile.getHandle();
    }

    private CreatorPublicProfile profile(UUID tenantId) {
        return profileService.requireProfile(tenantId);
    }

    private static OfficialUploadView toView(OfficialUploadJob job) {
        return new OfficialUploadView(job.getStatus(), job.getYoutubeVideoId(), job.getError(), job.getAttempts());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
