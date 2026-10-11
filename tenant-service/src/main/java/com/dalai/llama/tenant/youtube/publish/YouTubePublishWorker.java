package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.security.CredentialEncryptor;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Connection;
import com.dalai.llama.tenant.youtube.publish.YouTubeApiClient.Failure;
import com.dalai.llama.tenant.youtube.publish.YouTubeApiClient.UploadState;
import com.dalai.llama.tenant.youtube.publish.YouTubeApiClient.YouTubeCallException;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Job;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule 35: uploads one queued film at a time to its creator's channel. Resumable and chunked: the
 * session is created once, each run asks YouTube how much it has, then streams 8 MiB chunks read
 * from MinIO with Range requests. A crash or a failed chunk resumes from YouTube's offset. */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubePublishWorker {

    /** Must be a multiple of 256 KiB (YouTube's resumable protocol). */
    static final long CHUNK = 8L * 1024 * 1024;
    static final Duration LEASE = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 6;
    static final Duration STAMP_WAIT = Duration.ofMinutes(2);
    static final String STAMP_NOTE = "Adding the Dalaillama stamp to the film; the upload starts right after.";
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)};

    private final YouTubePublishStore store;
    private final YouTubeConnectionStore connectionStore;
    private final YouTubeConnectionService connections;
    private final PreProductionShowcaseClient preProduction;
    private final FilmRangeReader film;
    private final YouTubeApiClient youTube;
    private final CredentialEncryptor encryptor;
    private final YouTubeQuotaService quota;
    private final org.springframework.beans.factory.ObjectProvider<com.dalai.llama.tenant.youtube.service.ChannelImportService> channelImport;
    private final org.springframework.beans.factory.ObjectProvider<com.dalai.llama.tenant.showcase.service.AutoShowcaseService> autoShowcase;
    private final org.springframework.beans.factory.ObjectProvider<com.dalai.llama.tenant.showcase.service.PlatformPublishService> platformFilms;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${youtube-publish.poll-ms:20000}", initialDelayString = "${youtube-publish.poll-ms:20000}")
    public void scheduled() {
        runOnce();
    }

    /** Claims and works one due job. Returns whether there was one. */
    public synchronized boolean runOnce() {
        Instant now = clock.instant();
        Optional<UUID> claimed = store.claimDue(now, now.plus(LEASE));
        if (claimed.isEmpty()) return false;
        Job job = store.load(claimed.get()).orElseThrow();
        try {
            process(job);
        } catch (YouTubeConnectionService.ReconnectRequiredException e) {
            store.fail(job.id(), e.getMessage());
        } catch (YouTubeCallException e) {
            handle(job, e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            store.fail(job.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("YouTube publish job {} failed unexpectedly", job.id(), e);
            handle(job, new YouTubeCallException(Failure.TRANSIENT, e.getMessage()));
        }
        return true;
    }

    private void process(Job job) {
        Connection connection = connectionStore.find(job.connectionId()).orElseThrow(() -> new IllegalStateException("Channel removed"));
        PreProductionShowcaseClient.ShowcaseSource source = preProduction.source(job.tenantId(), job.projectId());
        if (!source.filmReady()) throw new IllegalStateException("The film is no longer available");
        String sourceUrl = source.stampedDownloadUrl();
        if (sourceUrl == null) {
            // Every upload carries the Dalaillama mark; post-production adds it shortly after publishing.
            store.waitForQuota(job.id(), clock.instant().plus(STAMP_WAIT), STAMP_NOTE);
            return;
        }

        String session = job.uploadUrlEnc() == null ? null : encryptor.decrypt(job.uploadUrlEnc());
        long total = job.bytesTotal() == null ? 0 : job.bytesTotal();
        if (session == null) {
            // Starting an upload is what YouTube charges for (rule 41): only when today's budget can pay.
            if (!quota.tryReserveUpload()) {
                store.waitForQuota(job.id(), quota.nextReset(),
                        "Waiting for YouTube's daily upload limit; it continues automatically after the reset.");
                return;
            }
            final long length = film.size(sourceUrl);
            total = length;
            session = withToken(connection, token -> youTube.startUpload(token, metadata(job), length));
            store.saveSession(job.id(), encryptor.encrypt(session), total);
        }
        final String uploadSession = session;
        final long size = total;
        UploadState state = withToken(connection, token -> youTube.status(token, uploadSession, size));
        while (!state.done()) {
            if (store.status(job.id()) == Status.CANCELLED) return;
            long offset = state.nextOffset();
            long length = Math.min(CHUNK, size - offset);
            try (InputStream chunk = film.read(sourceUrl, offset, length)) {
                state = youTube.sendChunk(connections.accessToken(connection), uploadSession, chunk, offset, length, size);
            } catch (IOException e) {
                throw new YouTubeCallException(Failure.TRANSIENT, "Reading the film failed: " + e.getMessage());
            } catch (YouTubeCallException e) {
                if (e.failure() != Failure.UNAUTHORIZED) throw e;
                // The token expired mid-chunk; that chunk's stream is spent, so ask where YouTube stopped.
                connections.forget(connection.id());
                state = withToken(connection, token -> youTube.status(token, uploadSession, size));
            }
            store.progress(job.id(), state.done() ? size : state.nextOffset(), clock.instant().plus(LEASE));
        }
        finish(job, connection, state);
    }

    private void finish(Job job, Connection connection, UploadState state) {
        store.thumbnail(job.id()).filter(t -> quota.tryReserve(YouTubeQuotaService.EDIT_UNITS, false)).ifPresent(thumbnail -> {
            try {
                withToken(connection, token -> {
                    youTube.setThumbnail(token, state.videoId(), thumbnail.bytes(), thumbnail.contentType());
                    return null;
                });
                store.clearThumbnail(job.id());
            } catch (YouTubeCallException e) {
                // The video is up; a thumbnail problem doesn't undo it. Shown on the job.
                log.warn("Thumbnail for {} failed: {}", state.videoId(), e.getMessage());
            }
        });
        store.complete(job.id(), state.videoId(), state.privacyStatus(), job.publishAt() != null ? Status.SCHEDULED : Status.PUBLISHED);
        recordOnProfile(job.tenantId(), job.projectId(), state.videoId(), state.privacyStatus());
        if (job.publishAt() == null) showOnProfile(job.tenantId());
        if (store.thumbnail(job.id()).isPresent()) store.noteError(job.id(), "Uploaded, but YouTube refused the thumbnail; try setting it again.");
        log.info("Published job {} as YouTube video {}", job.id(), state.videoId());
    }

    private void handle(Job job, YouTubeCallException e) {
        switch (e.failure()) {
            case SESSION_GONE -> {
                store.clearSession(job.id());
                store.retryLater(job.id(), job.attempts() + 1, clock.instant(), "Upload session expired; starting again");
            }
            case TRANSIENT -> {
                int attempts = job.attempts() + 1;
                if (attempts >= MAX_ATTEMPTS) store.fail(job.id(), "Gave up after " + attempts + " tries: " + e.getMessage());
                else store.retryLater(job.id(), attempts, clock.instant().plus(BACKOFF[attempts - 1]), e.getMessage());
            }
            case QUOTA -> store.fail(job.id(), "YouTube's upload limit was reached; retry later. (" + e.getMessage() + ")");
            case UNAUTHORIZED -> store.fail(job.id(), "YouTube refused our access; reconnect your channel and retry.");
            default -> store.fail(job.id(), "YouTube refused the video: " + e.getMessage());
        }
    }

    /** Calls YouTube; on a 401 refreshes the token once and tries again. */
    private <T> T withToken(Connection connection, java.util.function.Function<String, T> call) {
        try {
            return call.apply(connections.accessToken(connection));
        } catch (YouTubeCallException e) {
            if (e.failure() != Failure.UNAUTHORIZED) throw e;
            connections.forget(connection.id());
            return call.apply(connections.accessToken(connection));
        }
    }

    private static YouTubeApiClient.Metadata metadata(Job job) {
        List<String> tags = job.tags() == null ? List.of() : Arrays.asList(job.tags().split(","));
        return new YouTubeApiClient.Metadata(job.title(), job.description(), tags, job.categoryId(),
                job.privacy().name().toLowerCase(), job.publishAt());
    }

    /** The film is a Dalaillama film: it goes on the profile as one ("Made on Dalaillama"), whatever its
     * privacy -- the profile only shows it once it is public on YouTube. Best-effort. */
    void recordOnProfile(UUID tenantId, UUID projectId, String videoId, String privacy) {
        try {
            platformFilms.ifAvailable(p -> p.recordCreatorUpload(tenantId, projectId, videoId, privacy));
        } catch (RuntimeException e) {
            log.warn("Published, but couldn't record project {} on tenant {}'s profile: {}", projectId, tenantId, e.getMessage());
        }
    }

    /** A film published to the creator's channel shows on their profile right away (public videos
     * only: the profile never shows private or unlisted ones). Best-effort: the profile fill job and
     * the channel refresh catch up later if YouTube hasn't listed it yet. */
    void showOnProfile(UUID tenantId) {
        try {
            channelImport.ifAvailable(i -> i.importAll(tenantId));
            autoShowcase.ifAvailable(a -> a.fill(tenantId));
        } catch (RuntimeException e) {
            log.warn("Published, but couldn't put it on tenant {}'s profile yet: {}", tenantId, e.getMessage());
        }
    }

    /** Catch-up queue: finished uploads that never reached the profile (published before this existed,
     * or the instant step failed) are added a few per round, so neither YouTube nor pre-production is
     * hit in a burst. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${youtube-publish.profile-catch-up-ms:300000}", initialDelayString = "PT4M")
    public int catchUpProfiles() {
        List<Job> due = store.publishedNotOnProfile(PROFILE_CATCH_UP_BATCH);
        for (Job job : due) recordOnProfile(job.tenantId(), job.projectId(), job.videoId(), job.youtubePrivacy());
        return due.size();
    }

    static final int PROFILE_CATCH_UP_BATCH = 5;

    /** Scheduled publishes: once their time passes, the video is public, so it can go on the profile. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${youtube-publish.scheduled-check-ms:600000}", initialDelayString = "PT3M")
    public void scheduledNowPublic() {
        for (Job job : store.scheduledDue(clock.instant())) {
            store.markPublished(job.id());
            showOnProfile(job.tenantId());
        }
    }
}
