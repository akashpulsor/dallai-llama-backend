package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.oauth.GoogleOAuthClient;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Rule 37: the creator's own YouTube Analytics, read live with their token for the Marketing tab.
 * Cached for an hour in memory; never stored, never used in ranking (rule 6). */
@Service
public class YouTubeAnalyticsService {

    private static final Duration CACHE_FOR = Duration.ofHours(1);

    private final YouTubeConnectionService connections;
    private final GoogleOAuthClient google;
    private final YouTubeProperties youTube;
    private final String analyticsUrl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public YouTubeAnalyticsService(YouTubeConnectionService connections, GoogleOAuthClient google, YouTubeProperties youTube,
                                   @Value("${youtube.analytics-url:https://youtubeanalytics.googleapis.com/v2}") String analyticsUrl,
                                   Clock clock) {
        this.connections = connections;
        this.google = google;
        this.youTube = youTube;
        this.analyticsUrl = analyticsUrl;
        this.clock = clock;
    }

    public record Totals(long views, long minutesWatched, long averageViewDurationSeconds, long likes, long subscribersGained) {
    }

    public record Day(LocalDate date, long views, long minutesWatched) {
    }

    public record TopVideo(String videoId, String title, long views, long minutesWatched) {
    }

    public record Report(String channelTitle, int days, LocalDate startDate, LocalDate endDate, Totals totals, List<Day> daily,
                         List<TopVideo> topVideos) {
    }

    private record Cached(Report report, Instant at) {
    }

    public Report report(UUID tenantId, int days) {
        int window = Math.max(7, Math.min(days, 90));
        String key = tenantId + ":" + window;
        Cached hit = cache.get(key);
        if (hit != null && hit.at().plus(CACHE_FOR).isAfter(clock.instant())) return hit.report();

        Connection connection = connections.creatorConnection(tenantId)
                .orElseThrow(() -> new IllegalStateException("Connect your YouTube channel to see its analytics"));
        if (!connection.scopes().contains(YouTubeConnectionService.SCOPE_ANALYTICS)) {
            throw new IllegalStateException("Reconnect your channel and allow analytics to see them here");
        }
        String token = connections.accessToken(connection);
        // YouTube Analytics data settles with a ~2 day delay.
        LocalDate end = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        LocalDate start = end.minusDays(window - 1L);
        String range = "&startDate=" + start + "&endDate=" + end;
        String base = analyticsUrl + "/reports?ids=channel==MINE" + range;

        JsonNode totalsBody = google.getJson(base + "&metrics=views,estimatedMinutesWatched,averageViewDuration,likes,subscribersGained", token);
        List<Map<String, Long>> totalRows = rows(totalsBody);
        Map<String, Long> t = totalRows.isEmpty() ? Map.of() : totalRows.get(0);
        Totals totals = new Totals(t.getOrDefault("views", 0L), t.getOrDefault("estimatedMinutesWatched", 0L),
                t.getOrDefault("averageViewDuration", 0L), t.getOrDefault("likes", 0L), t.getOrDefault("subscribersGained", 0L));

        List<Day> daily = new ArrayList<>();
        JsonNode dailyBody = google.getJson(base + "&metrics=views,estimatedMinutesWatched&dimensions=day&sort=day", token);
        dailyBody.path("rows").forEach(row -> daily.add(new Day(LocalDate.parse(row.path(0).asText()), row.path(1).asLong(), row.path(2).asLong())));

        JsonNode topBody = google.getJson(base + "&metrics=views,estimatedMinutesWatched&dimensions=video&sort=-views&maxResults=10", token);
        List<String> ids = new ArrayList<>();
        topBody.path("rows").forEach(row -> ids.add(row.path(0).asText()));
        Map<String, String> titles = titles(ids, token);
        List<TopVideo> top = new ArrayList<>();
        topBody.path("rows").forEach(row -> top.add(new TopVideo(row.path(0).asText(), titles.getOrDefault(row.path(0).asText(), ""),
                row.path(1).asLong(), row.path(2).asLong())));

        Report report = new Report(connection.channelTitle(), window, start, end, totals, daily, top);
        cache.put(key, new Cached(report, clock.instant()));
        return report;
    }

    private Map<String, String> titles(List<String> ids, String token) {
        Map<String, String> titles = new HashMap<>();
        if (ids.isEmpty()) return titles;
        google.getJson(youTube.baseUrl() + "/videos?part=snippet&id=" + String.join(",", ids), token).path("items")
                .forEach(item -> titles.put(item.path("id").asText(), item.path("snippet").path("title").asText("")));
        return titles;
    }

    /** Analytics answers columns + rows; this turns each row into metric → value. */
    private static List<Map<String, Long>> rows(JsonNode body) {
        List<String> columns = new ArrayList<>();
        body.path("columnHeaders").forEach(h -> columns.add(h.path("name").asText()));
        List<Map<String, Long>> out = new ArrayList<>();
        body.path("rows").forEach(row -> {
            Map<String, Long> values = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) values.put(columns.get(i), row.path(i).asLong());
            out.add(values);
        });
        return out;
    }
}
