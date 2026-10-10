package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.OfficialUploadJob;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.OfficialUploadJobRepository;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.client.YouTubeUploadClient;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.YouTubeVideoCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** Uploads queued films to Dalaillama's channel, one at a time (the queue-row-plus-one-worker
 * pattern). A crash mid-upload leaves the row UPLOADING; after an hour it is picked up again. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialUploadWorker {

    static final int MAX_ATTEMPTS = 3;
    static final Duration STUCK_AFTER = Duration.ofHours(1);

    private final OfficialUploadJobRepository jobRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final PreProductionShowcaseClient preProduction;
    private final PlatformPublishService publishService;
    private final YouTubeUploadClient uploadClient;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache videoCache;
    private final YouTubeVideoRepository videoRepository;
    private final VideoHostProperties hostProperties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${video-host.official-channel.poll-ms:30000}")
    public void runOnce() {
        if (!hostProperties.officialChannel().configured()) return;
        requeueStuck();
        jobRepository.findFirstByStatusOrderByCreatedAtAsc(OfficialUploadStatus.QUEUED).ifPresent(this::process);
    }

    void process(OfficialUploadJob job) {
        job.setStatus(OfficialUploadStatus.UPLOADING);
        job.setAttempts(job.getAttempts() + 1);
        jobRepository.saveAndFlush(job);
        try {
            CreatorPublicProfile profile = profileRepository.findById(job.getTenantId()).orElseThrow();
            ShowcaseSource source = preProduction.source(job.getTenantId(), job.getProjectId());
            if (!source.filmReady() || source.downloadUrl() == null) throw new IllegalStateException("The film isn't published any more");

            YouTubeUploadClient.UploadResult result = uploadClient.upload(new YouTubeUploadClient.UploadRequest(
                    publishService.officialTitle(profile, job.getClientLabel()),
                    publishService.officialDescription(profile),
                    publishService.tags(job.getIndustry()),
                    source.downloadUrl()));
            job.setYoutubeVideoId(result.videoId());

            String asked = hostProperties.officialChannel().privacy();
            if (result.privacyStatus() != null && !result.privacyStatus().equals(asked)) {
                job.setStatus(OfficialUploadStatus.NEEDS_MANUAL);
                job.setError("YouTube set the upload to " + result.privacyStatus() + " instead of " + asked
                        + "; publish it in YouTube Studio, then link it");
                jobRepository.save(job);
                return;
            }
            cacheVideo(result.videoId(), source);
            publishService.completeOfficialUpload(job, source, result.videoId());
            job.setStatus(OfficialUploadStatus.DONE);
            job.setError(null);
            jobRepository.save(job);
            log.info("Official upload done project={} video={}", job.getProjectId(), result.videoId());
        } catch (RuntimeException e) {
            boolean giveUp = job.getAttempts() >= MAX_ATTEMPTS;
            job.setStatus(giveUp ? OfficialUploadStatus.FAILED : OfficialUploadStatus.QUEUED);
            job.setError(truncate(e.getMessage()));
            jobRepository.save(job);
            log.warn("Official upload attempt {} failed project={}: {}", job.getAttempts(), job.getProjectId(), e.getMessage());
        }
    }

    /** YouTube may still be processing a fresh upload and not list it yet; the film's own facts
     * stand in until the nightly refresh replaces them. */
    private void cacheVideo(String videoId, ShowcaseSource source) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<YouTubeDataClient.VideoInfo> listed = youTube.videos(List.of(videoId));
        if (!listed.isEmpty()) {
            videoCache.store(List.of(videoId), listed, now);
            return;
        }
        videoRepository.save(YouTubeVideo.builder()
                .videoId(videoId)
                .channelId("OFFICIAL")
                .title(source.projectName())
                .durationSeconds(source.durationSeconds() == null ? BigDecimal.ZERO : source.durationSeconds())
                .aspectW(source.width())
                .aspectH(source.height())
                .privacyStatus(hostProperties.officialChannel().privacy())
                .embeddable(true)
                .ageRestricted(false)
                .madeForKids(false)
                .publishedAt(now)
                .fetchedAt(now)
                .build());
    }

    private void requeueStuck() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(STUCK_AFTER);
        jobRepository.findByStatusIn(List.of(OfficialUploadStatus.UPLOADING)).stream()
                .filter(j -> j.getUpdatedAt().isBefore(cutoff))
                .forEach(j -> {
                    j.setStatus(j.getAttempts() >= MAX_ATTEMPTS ? OfficialUploadStatus.FAILED : OfficialUploadStatus.QUEUED);
                    jobRepository.save(j);
                });
    }

    private static String truncate(String s) {
        if (s == null) return "Unknown error";
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
