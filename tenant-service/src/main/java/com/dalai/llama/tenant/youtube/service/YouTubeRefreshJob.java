package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.youtube.client.ChannelRef;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.client.YouTubeUnavailableException;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Keeps us inside YouTube's 30-day rule for cached API data (CREATOR_SHOWCASE.md rule 6):
 * anything fetched more than {@code refresh-after-days} ago is fetched again, and videos YouTube
 * no longer serves are marked gone. If YouTube is down the run stops and the next one carries on;
 * public reads hide anything that slips past 30 days. */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeRefreshJob {

    private final YouTubeVideoRepository videoRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache cache;
    private final YouTubeProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${youtube.refresh-cron:0 0 2 * * *}")
    public void run() {
        if (!properties.configured()) return;
        try {
            int channels = refreshChannels();
            int videos = refreshVideos();
            log.info("YouTube refresh: channels={} videos={}", channels, videos);
        } catch (YouTubeUnavailableException e) {
            log.warn("YouTube refresh stopped early; the next run continues: {}", e.getMessage());
        }
    }

    int refreshVideos() {
        OffsetDateTime cutoff = cutoff();
        List<YouTubeVideo> stale = videoRepository.findByGoneAtIsNullAndFetchedAtBeforeOrderByFetchedAtAsc(
                cutoff, PageRequest.of(0, properties.refreshBatchSize()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int from = 0; from < stale.size(); from += YouTubeDataClient.MAX_IDS_PER_CALL) {
            List<String> ids = stale.subList(from, Math.min(from + YouTubeDataClient.MAX_IDS_PER_CALL, stale.size()))
                    .stream().map(YouTubeVideo::getVideoId).toList();
            cache.store(ids, youTube.videos(ids), now);
        }
        return stale.size();
    }

    int refreshChannels() {
        List<CreatorYouTubeChannel> stale = channelRepository.findByFetchedAtBefore(cutoff());
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (CreatorYouTubeChannel channel : stale) {
            Optional<YouTubeDataClient.ChannelInfo> info = youTube.findChannel(new ChannelRef.ById(channel.getChannelId()));
            channel.setChannelTitle(info.map(YouTubeDataClient.ChannelInfo::title).orElse(null));
            channel.setChannelThumbnailUrl(info.map(YouTubeDataClient.ChannelInfo::thumbnailUrl).orElse(null));
            channel.setFetchedAt(now);
            channelRepository.save(channel);
        }
        return stale.size();
    }

    private OffsetDateTime cutoff() {
        return OffsetDateTime.now(clock).minusDays(properties.refreshAfterDays());
    }
}
