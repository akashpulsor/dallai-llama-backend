package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Connection;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Status;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.EditRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.JobView;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishableFilm;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Job;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.NewJob;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Privacy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Rule 34 and 36: a creator publishing their finished Dalaillama films to their own connected
 * channel, and editing what was published. The upload itself is {@link YouTubePublishWorker}'s. */
@Service
@RequiredArgsConstructor
public class YouTubePublishService {

    /** "People & Blogs": YouTube needs a category and this is the neutral one. */
    static final String DEFAULT_CATEGORY = "22";
    static final long MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024;
    private static final Set<String> THUMBNAIL_TYPES = Set.of("image/jpeg", "image/png");
    private static final Duration MIN_SCHEDULE_AHEAD = Duration.ofMinutes(15);

    private final YouTubePublishStore store;
    private final YouTubeConnectionService connections;
    private final PreProductionShowcaseClient preProduction;
    private final YouTubeApiClient youTube;
    private final YouTubeQuotaService quota;
    private final Clock clock;

    public List<PublishableFilm> publishableFilms(UUID tenantId) {
        return preProduction.films(tenantId).stream()
                .map(f -> new PublishableFilm(f.projectId(), f.projectName(), f.renderedAt(), f.durationSeconds(), f.width(), f.height(),
                        f.marketingTermsAcceptedAt() != null))
                .toList();
    }

    @Transactional
    public JobView publish(UUID tenantId, String subject, PublishRequest r, String via) {
        var existing = store.byKey(tenantId, r.idempotencyKey());
        if (existing.isPresent()) return view(existing.get());
        Connection connection = activeConnection(tenantId);
        var active = store.activeFor(tenantId, r.projectId(), connection.id());
        if (active.isPresent()) return view(active.get());

        Instant now = clock.instant();
        checkSchedule(r.publishAt(), now);
        boolean goesPublic = r.privacy() == Privacy.PUBLIC || r.publishAt() != null;
        if (goesPublic && !r.confirmPublic()) {
            throw new IllegalArgumentException("Confirm that this video will be public on YouTube");
        }
        ShowcaseSource film = preProduction.source(tenantId, r.projectId());
        if (!film.filmReady()) throw new IllegalStateException("This film isn't finished yet");
        if (r.privacy() != Privacy.PRIVATE && film.marketingTermsAcceptedAt() == null) {
            throw new IllegalStateException("Your client hasn't agreed to marketing use of this film, so it can only be uploaded as private");
        }
        UUID id = store.insert(new NewJob(tenantId, connection.id(), r.projectId(), r.idempotencyKey(), r.title().trim(),
                r.description() == null ? "" : r.description().trim(), tags(r.tags()),
                r.categoryId() == null ? DEFAULT_CATEGORY : r.categoryId(), r.privacy(), r.publishAt(), goesPublic ? now : null, via, subject, now));
        return view(store.load(id).orElseThrow());
    }

    public List<JobView> jobs(UUID tenantId) {
        return store.recent(tenantId, 50).stream().map(YouTubePublishService::view).toList();
    }

    public JobView job(UUID tenantId, UUID id) {
        return view(owned(tenantId, id));
    }

    @Transactional
    public void cancel(UUID tenantId, UUID id) {
        if (store.cancel(tenantId, id) == 0) throw new IllegalStateException("Only a waiting or uploading job can be cancelled");
    }

    @Transactional
    public JobView retry(UUID tenantId, UUID id) {
        activeConnection(tenantId);
        if (store.requeue(tenantId, id, clock.instant()) == 0) throw new IllegalStateException("Only a failed job can be retried");
        return job(tenantId, id);
    }

    /** Before upload the thumbnail waits in the job; after, it goes straight to YouTube. */
    @Transactional
    public JobView setThumbnail(UUID tenantId, UUID id, byte[] image, String contentType) {
        if (image.length == 0 || image.length > MAX_THUMBNAIL_BYTES) throw new IllegalArgumentException("A thumbnail must be at most 2 MB");
        if (contentType == null || !THUMBNAIL_TYPES.contains(contentType)) throw new IllegalArgumentException("Use a JPEG or PNG thumbnail");
        Job job = owned(tenantId, id);
        if (job.videoId() == null) {
            store.setThumbnail(id, image, contentType);
        } else {
            Connection connection = activeConnection(tenantId);
            requireQuota();
            youTube.setThumbnail(connections.accessToken(connection), job.videoId(), image, contentType);
        }
        return job(tenantId, id);
    }

    /** Rule 36: change what's on YouTube; making it public needs the same confirmation. */
    @Transactional
    public JobView edit(UUID tenantId, UUID id, EditRequest r) {
        Job job = owned(tenantId, id);
        if (job.videoId() == null) throw new IllegalStateException("This video isn't on YouTube yet");
        Instant now = clock.instant();
        checkSchedule(r.publishAt(), now);
        boolean goesPublic = r.privacy() == Privacy.PUBLIC || r.publishAt() != null;
        if (goesPublic && !r.confirmPublic() && job.publicConfirmedAt() == null) {
            throw new IllegalArgumentException("Confirm that this video will be public on YouTube");
        }
        Connection connection = activeConnection(tenantId);
        requireQuota();
        String privacy = youTube.updateVideo(connections.accessToken(connection), job.videoId(), new YouTubeApiClient.Metadata(
                r.title().trim(), r.description() == null ? "" : r.description().trim(), r.tags() == null ? List.of() : r.tags(),
                job.categoryId(), r.privacy().name().toLowerCase(), r.publishAt()));
        store.updateMetadata(id, r.title().trim(), r.description() == null ? "" : r.description().trim(), tags(r.tags()), r.privacy(),
                r.publishAt(), goesPublic ? now : null, privacy,
                r.publishAt() != null ? YouTubePublishStore.Status.SCHEDULED : YouTubePublishStore.Status.PUBLISHED);
        return job(tenantId, id);
    }

    private Connection activeConnection(UUID tenantId) {
        Connection c = connections.creatorConnection(tenantId)
                .orElseThrow(() -> new IllegalStateException("Connect your YouTube channel first"));
        if (c.status() != Status.ACTIVE) throw new YouTubeConnectionService.ReconnectRequiredException();
        return c;
    }

    private void requireQuota() {
        if (!quota.tryReserve(YouTubeQuotaService.EDIT_UNITS, false)) {
            throw new IllegalStateException("YouTube's daily limit for Dalai Llama is used up; try again after " + quota.nextReset());
        }
    }

    private void checkSchedule(Instant publishAt, Instant now) {
        if (publishAt != null && publishAt.isBefore(now.plus(MIN_SCHEDULE_AHEAD))) {
            throw new IllegalArgumentException("Schedule at least 15 minutes ahead");
        }
    }

    private Job owned(UUID tenantId, UUID id) {
        return store.find(tenantId, id).orElseThrow(() -> new IllegalArgumentException("No such publishing job"));
    }

    private static String tags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        String joined = String.join(",", tags.stream().map(String::trim).filter(t -> !t.isEmpty() && !t.contains(",")).toList());
        if (joined.length() > 500) throw new IllegalArgumentException("Tags can be at most 500 characters in total");
        return joined;
    }

    static JobView view(Job j) {
        int percent = j.bytesTotal() == null || j.bytesTotal() == 0 ? 0 : (int) Math.min(100, j.bytesSent() * 100 / j.bytesTotal());
        return new JobView(j.id(), j.projectId(), j.title(), j.description(),
                j.tags() == null ? List.of() : Arrays.asList(j.tags().split(",")), j.privacy(), j.publishAt(), j.status(), j.bytesTotal(),
                j.bytesSent(), percent, j.videoId(), j.videoId() == null ? null : "https://www.youtube.com/watch?v=" + j.videoId(),
                j.youtubePrivacy(), j.hasThumbnail(), j.attempts(), j.lastError(), j.via(), j.createdAt(), j.updatedAt());
    }
}
