package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.dto.YouTubeVideoView;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reads a verified channel's uploads into our cache, and lists them back to the creator with
 * whether each one can be showcased. Costs about 1 quota unit per 50 videos. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelImportService {

    /** A creator can re-sync at most this often. */
    static final Duration SYNC_COOLDOWN = Duration.ofMinutes(10);
    private static final int DEFAULT_ASPECT_W = 16;
    private static final int DEFAULT_ASPECT_H = 9;

    private final CreatorYouTubeChannelRepository channelRepository;
    private final YouTubeVideoRepository videoRepository;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache cache;
    private final VideoEligibility eligibility;
    private final YouTubeProperties properties;
    private final Clock clock;

    public int importAll(UUID tenantId) {
        CreatorYouTubeChannel channel = verifiedChannel(tenantId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Set<String> ids = new LinkedHashSet<>();
        String pageToken = null;
        do {
            YouTubeDataClient.PlaylistPage page = youTube.playlistItems(channel.getUploadsPlaylistId(), pageToken);
            ids.addAll(page.videoIds());
            pageToken = page.nextPageToken();
        } while (pageToken != null && ids.size() < properties.importMaxVideos());
        List<String> wanted = ids.stream().limit(properties.importMaxVideos()).toList();
        boolean readWholeChannel = pageToken == null && ids.size() <= properties.importMaxVideos();

        for (int from = 0; from < wanted.size(); from += YouTubeDataClient.MAX_IDS_PER_CALL) {
            List<String> batch = wanted.subList(from, Math.min(from + YouTubeDataClient.MAX_IDS_PER_CALL, wanted.size()));
            cache.store(batch, youTube.videos(batch), now);
        }
        // Only a complete read tells us which uploads disappeared; a capped read would wrongly
        // retire the channel's older videos.
        if (readWholeChannel) markRemovedUploadsGone(channel.getChannelId(), wanted);

        channel.setLastSyncedAt(now);
        channelRepository.save(channel);
        log.info("Imported {} videos tenant={} channel={}", wanted.size(), tenantId, channel.getChannelId());
        return wanted.size();
    }

    /** Creator-triggered re-import, limited to once per {@link #SYNC_COOLDOWN}. */
    public int resync(UUID tenantId) {
        CreatorYouTubeChannel channel = verifiedChannel(tenantId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (channel.getLastSyncedAt() != null && channel.getLastSyncedAt().plus(SYNC_COOLDOWN).isAfter(now)) {
            throw new IllegalStateException("Your channel was synced a moment ago; try again in a few minutes");
        }
        return importAll(tenantId);
    }

    public List<YouTubeVideoView> listVideos(UUID tenantId) {
        Optional<CreatorYouTubeChannel> channel = channelRepository.findById(tenantId)
                .filter(c -> c.getStatus() == ChannelStatus.VERIFIED);
        if (channel.isEmpty()) return List.of();
        return videoRepository.findByChannelIdAndGoneAtIsNullOrderByPublishedAtDesc(channel.get().getChannelId())
                .stream().map(this::toView).toList();
    }

    /** Videos we cached from this channel that are no longer among its uploads were deleted or
     * made private; never show them again. */
    private void markRemovedUploadsGone(String channelId, List<String> currentUploads) {
        List<String> missing = new ArrayList<>();
        for (YouTubeVideo cached : videoRepository.findByChannelIdAndGoneAtIsNullOrderByPublishedAtDesc(channelId)) {
            if (!currentUploads.contains(cached.getVideoId())) missing.add(cached.getVideoId());
        }
        if (!missing.isEmpty()) cache.store(missing, List.of(), OffsetDateTime.now(clock));
    }

    private CreatorYouTubeChannel verifiedChannel(UUID tenantId) {
        return channelRepository.findById(tenantId)
                .filter(c -> c.getStatus() == ChannelStatus.VERIFIED)
                .orElseThrow(() -> new IllegalStateException("Verify your YouTube channel first"));
    }

    private YouTubeVideoView toView(YouTubeVideo v) {
        int w = v.getAspectW() == null ? DEFAULT_ASPECT_W : v.getAspectW();
        int h = v.getAspectH() == null ? DEFAULT_ASPECT_H : v.getAspectH();
        boolean vertical = h > w;
        Optional<VideoEligibility.Reason> problem = eligibility.problem(v);
        return new YouTubeVideoView(
                v.getVideoId(),
                v.getTitle(),
                v.getThumbnailUrl(),
                v.getPublishedAt(),
                v.getDurationSeconds(),
                w,
                h,
                vertical,
                problem.isEmpty(),
                problem.map(Enum::name).orElse(null),
                YouTubeLinks.watch(v.getVideoId(), vertical, v.getDurationSeconds()));
    }
}
