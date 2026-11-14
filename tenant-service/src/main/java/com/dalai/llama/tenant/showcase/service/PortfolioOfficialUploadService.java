package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PostProductionStampClient;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.VideoChannel;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.OfficialUploadJob;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.OfficialUploadJobRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.client.YouTubeUploadClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Creators' uploaded portfolio films on Dalaillama's YouTube channel, so pages stream them from
 * YouTube instead of our full-size files. Every creator's live uploads are queued; each is stamped
 * with the Dalaillama mark by post-production, uploaded by OfficialUploadWorker crediting the
 * creator and Creator Studio, and then plays from YouTube (ShowcaseItem#playsOwnCopy). The upload
 * itself stays on our storage, with the poster the creator chose.
 */
@Service
@RequiredArgsConstructor
public class PortfolioOfficialUploadService {

    private final ShowcaseItemRepository itemRepository;
    private final OfficialUploadJobRepository jobRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final PostProductionStampClient stampClient;
    private final ShowcaseMediaStore mediaStore;
    private final PlatformPublishService publishService;
    private final VideoHostProperties hostProperties;
    private final ShowcaseProperties properties;

    /** Queues this creator's live uploads that aren't on YouTube yet. Returns how many were queued. */
    @Transactional
    public int queue(CreatorPublicProfile profile) {
        if (!hostProperties.officialChannel().portfolioUploads()) return 0;
        int queued = 0;
        for (ShowcaseItem item : itemRepository.findPortfolioUploadsToPublish(profile.getTenantId())) {
            if (jobRepository.findByShowcaseItemId(item.getId()).isPresent()) continue;
            jobRepository.save(OfficialUploadJob.builder().id(UUID.randomUUID()).tenantId(item.getTenantId())
                    .showcaseItemId(item.getId()).industry(item.getIndustry()).format(item.getFormat())
                    .clientLabel(item.getClientLabel()).status(OfficialUploadStatus.QUEUED).attempts(0).build());
            queued++;
        }
        return queued;
    }

    /** Where the job's stamped copy is: asks post-production on every call (the job id is the request id). */
    public PostProductionStampClient.Stamp stamp(OfficialUploadJob job) {
        Optional<ShowcaseItem> item = uploadable(job);
        if (item.isEmpty()) return new PostProductionStampClient.Stamp("FAILED", null, "The film isn't on the creator's profile any more");
        ShowcaseItem film = item.get();
        return stampClient.request(job.getId(), film.getTenantId(), mediaStore.bucket(), film.getUploadObjectKey(), film.getFilmWidth());
    }

    /** The upload for the worker to send, crediting the creator and Creator Studio. */
    OfficialUpload upload(OfficialUploadJob job, String stampedUrl) {
        ShowcaseItem item = uploadable(job).orElseThrow(() -> new IllegalStateException("The film isn't on the creator's profile any more"));
        CreatorPublicProfile profile = profileRepository.findById(job.getTenantId()).orElseThrow();
        String title = title(profile, item);
        return new OfficialUpload(
                new YouTubeUploadClient.UploadRequest(title, description(profile), publishService.tags(item.getIndustry()), stampedUrl),
                new OfficialUpload.Facts(title, item.getFilmDurationSeconds(), item.getFilmWidth(), item.getFilmHeight()),
                videoId -> moveToChannel(item.getId(), videoId));
    }

    /** The film now plays from Dalaillama's channel. */
    @Transactional
    public void moveToChannel(UUID itemId, String videoId) {
        ShowcaseItem item = itemRepository.findById(itemId).orElseThrow();
        item.setYoutubeVideoId(videoId);
        item.setChannel(VideoChannel.OFFICIAL);
        itemRepository.save(item);
    }

    static String title(CreatorPublicProfile profile, ShowcaseItem item) {
        String title = firstNonBlank(item.getTitleOverride(), item.getClientLabel(), "Portfolio film")
                + " · by " + profile.getDisplayName() + " · Dalaillama";
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private String description(CreatorPublicProfile profile) {
        return hostProperties.officialChannel().portfolioDescriptionTemplate()
                .replace("{creatorName}", profile.getDisplayName())
                .replace("{profileUrl}", properties.publicBaseUrl() + "/c/" + profile.getHandle());
    }

    private Optional<ShowcaseItem> uploadable(OfficialUploadJob job) {
        return itemRepository.findById(job.getShowcaseItemId())
                .filter(i -> i.getStatus() == ShowcaseItemStatus.LIVE && !i.isHiddenByOps())
                .filter(i -> i.getUploadObjectKey() != null && i.getYoutubeVideoId() == null);
    }
}
