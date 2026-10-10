package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Writes YouTube API answers into our cache. Shared by import and the 30-day refresh so both
 * store exactly the same facts the same way. */
@Component
@RequiredArgsConstructor
public class YouTubeVideoCache {

    private final YouTubeVideoRepository repository;

    /** Stores what YouTube returned for {@code requestedIds}; ids it did not return are marked
     * gone (deleted, made private, or otherwise no longer served). */
    @Transactional
    public void store(Collection<String> requestedIds, List<YouTubeDataClient.VideoInfo> returned, OffsetDateTime now) {
        Map<String, YouTubeVideo> existing = repository.findAllById(requestedIds).stream()
                .collect(Collectors.toMap(YouTubeVideo::getVideoId, Function.identity()));
        Set<String> returnedIds = returned.stream().map(YouTubeDataClient.VideoInfo::videoId).collect(Collectors.toSet());

        for (YouTubeDataClient.VideoInfo info : returned) {
            YouTubeVideo video = existing.getOrDefault(info.videoId(), YouTubeVideo.builder().videoId(info.videoId()).build());
            apply(video, info, now);
            repository.save(video);
        }
        existing.values().stream()
                .filter(v -> !returnedIds.contains(v.getVideoId()) && v.getGoneAt() == null)
                .forEach(v -> {
                    v.setGoneAt(now);
                    // Drop API data we may no longer keep; the id alone is ours.
                    v.setTitle(null);
                    v.setThumbnailUrl(null);
                    repository.save(v);
                });
    }

    private static void apply(YouTubeVideo video, YouTubeDataClient.VideoInfo info, OffsetDateTime now) {
        video.setChannelId(info.channelId());
        video.setTitle(truncate(info.title(), 200));
        video.setThumbnailUrl(info.thumbnailUrl());
        video.setPublishedAt(info.publishedAt() == null ? null : info.publishedAt().atOffset(ZoneOffset.UTC));
        video.setDurationSeconds(info.duration() == null ? null
                : BigDecimal.valueOf(info.duration().toMillis()).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP));
        video.setAspectW(info.embedWidth());
        video.setAspectH(info.embedHeight());
        video.setPrivacyStatus(info.privacyStatus());
        video.setEmbeddable(info.embeddable());
        video.setAgeRestricted(info.ageRestricted());
        video.setMadeForKids(info.madeForKids());
        video.setFetchedAt(now);
        video.setGoneAt(null);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
