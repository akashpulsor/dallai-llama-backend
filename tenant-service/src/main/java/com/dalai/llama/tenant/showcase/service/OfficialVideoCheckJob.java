package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.service.YouTubeVideoCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Asks YouTube about creators' uploads on Dalaillama's channel every hour (the nightly refresh
 * only gets to a video every few weeks). One deleted, made private or no longer embeddable is
 * marked so in the cache, and from then on plays from our own copy again
 * (PublicShowcaseService#hostFor). About one API unit per 50 films. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialVideoCheckJob {

    private final ShowcaseItemRepository itemRepository;
    private final YouTubeDataClient youTube;
    private final YouTubeVideoCache cache;
    private final YouTubeProperties youTubeProperties;
    private final Clock clock;

    @Scheduled(cron = "${video-host.official-channel.check-cron:0 15 * * * *}")
    public int run() {
        if (!youTubeProperties.configured()) return 0;
        List<String> ids = itemRepository.findOfficialPortfolioVideoIds();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            for (int from = 0; from < ids.size(); from += YouTubeDataClient.MAX_IDS_PER_CALL) {
                List<String> batch = ids.subList(from, Math.min(from + YouTubeDataClient.MAX_IDS_PER_CALL, ids.size()));
                cache.store(batch, youTube.videos(batch), now);
            }
        } catch (RuntimeException e) {
            log.warn("Checking films on Dalaillama's channel stopped early; the next run carries on: {}", e.getMessage());
        }
        return ids.size();
    }
}
