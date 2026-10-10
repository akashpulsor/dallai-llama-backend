package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Nightly check that YouTube still plays for us (§21.2): looks up one public video of ours and
 * records whether it is still public and embeddable. Shown on the AdminOps panel; deliberately no
 * alerting, ops reads the panel. Kept in memory: tenant-service runs one replica. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoHostHealth {

    private final YouTubeDataClient youTube;
    private final YouTubeProperties youTubeProperties;
    private final VideoHostProperties hostProperties;
    private final Clock clock;

    @Value("${video-host.health.probe-video-id:}")
    private String probeVideoId;

    private volatile Probe last;

    public record Probe(OffsetDateTime at, boolean ok, String message) {
    }

    public record HealthView(
            boolean youtubeApiConfigured,
            boolean officialChannelConnected,
            String platformFilmsHost,
            Probe lastProbe
    ) {
    }

    @Scheduled(cron = "${video-host.health.cron:0 15 2 * * *}")
    public Probe probe() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!youTubeProperties.configured() || probeVideoId == null || probeVideoId.isBlank()) {
            last = new Probe(now, false, "No API key or probe video configured");
            return last;
        }
        try {
            List<YouTubeDataClient.VideoInfo> found = youTube.videos(List.of(probeVideoId));
            if (found.isEmpty()) {
                last = new Probe(now, false, "The probe video is no longer served by YouTube");
            } else if (!found.get(0).embeddable() || !"public".equals(found.get(0).privacyStatus())) {
                last = new Probe(now, false, "The probe video can no longer be embedded publicly");
            } else {
                last = new Probe(now, true, "YouTube embeds are working");
            }
        } catch (RuntimeException e) {
            last = new Probe(now, false, "YouTube API error: " + e.getMessage());
        }
        if (!last.ok()) log.warn("Video host probe failed: {}", last.message());
        return last;
    }

    public HealthView view() {
        return new HealthView(youTubeProperties.configured(), hostProperties.officialChannel().configured(),
                hostProperties.platformFilms().name(), last);
    }
}
