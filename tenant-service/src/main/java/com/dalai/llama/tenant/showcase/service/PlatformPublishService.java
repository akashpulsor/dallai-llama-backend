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
    private final ShowcaseItemRepository itemRepository;
    private final OfficialUploadJobRepository uploadJobRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final CreatorProfileService profileService;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache videoCache;
    private final YouTubeVideoRepository videoRepository;
    private final PublicIdGenerator publicIds;
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
        return ShowcasePickService.toView(itemRepository.save(item), videoRepository.findById(videoId).orElse(null));
    }

    /** Path 1: queue an upload to Dalaillama's channel. Re-requesting after a failure re-queues it. */
    @Transactional
    public OfficialUploadView requestOfficialUpload(UUID tenantId, UUID projectId, OfficialUploadRequest request) {
        if (!hostProperties.officialChannel().configured()) {
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

    public Optional<OfficialUploadView> officialUpload(UUID tenantId, UUID projectId) {
        return uploadJobRepository.findByProjectId(projectId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .map(PlatformPublishService::toView);
    }

    /** Called by the worker once YouTube accepted the upload. */
    @Transactional
    public ShowcaseItem completeOfficialUpload(OfficialUploadJob job, ShowcaseSource source, String videoId) {
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
        if (!hostProperties.officialChannel().configured()) return Optional.of("Dalaillama's channel isn't connected yet");
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
