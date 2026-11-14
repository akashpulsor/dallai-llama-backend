package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PostProductionStampClient;
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
import java.util.Optional;
import java.util.function.Supplier;

/** Uploads queued films to Dalaillama's channel, one at a time (the queue-row-plus-one-worker
 * pattern): films made in a project, and creators' uploaded portfolio films. Each goes up as its
 * Dalaillama-stamped copy. A crash mid-upload leaves the row UPLOADING; after an hour it is picked
 * up again. */
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
    private final PortfolioOfficialUploadService portfolio;
    private final YouTubeUploadClient uploadClient;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache videoCache;
    private final YouTubeVideoRepository videoRepository;
    private final VideoHostProperties hostProperties;
    private final OfficialChannelAccess officialChannel;
    private final com.dalai.llama.tenant.youtube.publish.YouTubeQuotaService quota;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${video-host.official-channel.poll-ms:30000}")
    public void runOnce() {
        if (!officialChannel.ready()) return;
        requeueStuck();
        // The oldest job whose stamped film is ready; one still being stamped never holds up the rest.
        for (OfficialUploadJob job : jobRepository.findTop10ByStatusOrderByCreatedAtAsc(OfficialUploadStatus.QUEUED)) {
            if (job.getShowcaseItemId() != null) {
                Optional<String> stamped = portfolioStamped(job);
                if (stamped.isEmpty()) continue;
                if (!quota.tryReserveUpload()) return;
                processPortfolio(job, stamped.get());
                return;
            }
            ShowcaseSource source;
            try {
                source = preProduction.source(job.getTenantId(), job.getProjectId());
            } catch (RuntimeException e) {
                log.warn("Official upload project={}: film lookup failed: {}", job.getProjectId(), e.getMessage());
                continue;
            }
            if (source.filmReady() && source.stampedDownloadUrl() == null) {
                noteStamping(job);
                continue;
            }
            // The same project quota as creator uploads (rule 41): wait for the reset when it's spent.
            if (source.filmReady() && !quota.tryReserveUpload()) return;
            process(job, source);
            return;
        }
    }

    static final String STAMP_NOTE = "Adding the Dalaillama stamp to the film; the upload starts right after.";

    void process(OfficialUploadJob job, ShowcaseSource source) {
        send(job, () -> {
            CreatorPublicProfile profile = profileRepository.findById(job.getTenantId()).orElseThrow();
            if (!source.filmReady() || source.stampedDownloadUrl() == null) throw new IllegalStateException("The film isn't published any more");
            return new OfficialUpload(
                    new YouTubeUploadClient.UploadRequest(
                            publishService.officialTitle(profile, job.getClientLabel()),
                            publishService.officialDescription(profile),
                            publishService.tags(job.getIndustry()),
                            source.stampedDownloadUrl()),
                    new OfficialUpload.Facts(source.projectName(), source.durationSeconds(), source.width(), source.height()),
                    videoId -> publishService.completeOfficialUpload(job, source, videoId));
        });
    }

    void processPortfolio(OfficialUploadJob job, String stampedUrl) {
        send(job, () -> portfolio.upload(job, stampedUrl));
    }

    /** The stamped copy's link once post-production made it; a note while it's being made; the job
     * failed when the film left the profile or the stamp gave up. */
    private Optional<String> portfolioStamped(OfficialUploadJob job) {
        PostProductionStampClient.Stamp stamp;
        try {
            stamp = portfolio.stamp(job);
        } catch (RuntimeException e) {
            log.warn("Official upload item={}: stamp lookup failed: {}", job.getShowcaseItemId(), e.getMessage());
            return Optional.empty();
        }
        if (stamp.failed()) {
            job.setStatus(OfficialUploadStatus.FAILED);
            job.setError(truncate(stamp.error()));
            jobRepository.save(job);
            return Optional.empty();
        }
        if (stamp.stampedUrl() == null) {
            noteStamping(job);
            return Optional.empty();
        }
        return Optional.of(stamp.stampedUrl());
    }

    private void noteStamping(OfficialUploadJob job) {
        if (STAMP_NOTE.equals(job.getError())) return;
        job.setError(STAMP_NOTE);
        jobRepository.save(job);
    }

    /** Uploads one film and records the outcome on its job; {@code prepare} may throw to fail the attempt. */
    private void send(OfficialUploadJob job, Supplier<OfficialUpload> prepare) {
        job.setStatus(OfficialUploadStatus.UPLOADING);
        job.setAttempts(job.getAttempts() + 1);
        jobRepository.saveAndFlush(job);
        try {
            OfficialUpload upload = prepare.get();
            YouTubeUploadClient.UploadResult result = uploadClient.upload(upload.request());
            job.setYoutubeVideoId(result.videoId());

            String asked = hostProperties.officialChannel().privacy();
            if (result.privacyStatus() != null && !result.privacyStatus().equals(asked)) {
                job.setStatus(OfficialUploadStatus.NEEDS_MANUAL);
                job.setError("YouTube set the upload to " + result.privacyStatus() + " instead of " + asked
                        + "; publish it in YouTube Studio, then link it");
                jobRepository.save(job);
                return;
            }
            cacheVideo(result.videoId(), upload.facts());
            upload.complete().accept(result.videoId());
            job.setStatus(OfficialUploadStatus.DONE);
            job.setError(null);
            jobRepository.save(job);
            log.info("Official upload done job={} video={}", job.getId(), result.videoId());
        } catch (RuntimeException e) {
            boolean giveUp = job.getAttempts() >= MAX_ATTEMPTS;
            job.setStatus(giveUp ? OfficialUploadStatus.FAILED : OfficialUploadStatus.QUEUED);
            job.setError(truncate(e.getMessage()));
            jobRepository.save(job);
            log.warn("Official upload attempt {} failed job={}: {}", job.getAttempts(), job.getId(), e.getMessage());
        }
    }

    /** YouTube may still be processing a fresh upload and not list it yet; the film's own facts
     * stand in until the nightly refresh replaces them. */
    private void cacheVideo(String videoId, OfficialUpload.Facts facts) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<YouTubeDataClient.VideoInfo> listed = youTube.videos(List.of(videoId));
        if (!listed.isEmpty()) {
            videoCache.store(List.of(videoId), listed, now);
            return;
        }
        videoRepository.save(YouTubeVideo.builder()
                .videoId(videoId)
                .channelId("OFFICIAL")
                .title(facts.title())
                .durationSeconds(facts.durationSeconds() == null ? BigDecimal.ZERO : facts.durationSeconds())
                .aspectW(facts.width())
                .aspectH(facts.height())
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
