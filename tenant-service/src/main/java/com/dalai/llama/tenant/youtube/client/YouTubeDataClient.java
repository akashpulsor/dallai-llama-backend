package com.dalai.llama.tenant.youtube.client;

import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** The only class that talks to the YouTube Data API v3 with our API key (public data only, no
 * user OAuth). Each read costs 1 quota unit; {@link #videos} takes up to 50 ids per call. Nothing
 * above this class sees Google's JSON. */
@Slf4j
@Component
public class YouTubeDataClient {

    public static final int MAX_IDS_PER_CALL = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Asking for the player at this height makes YouTube return embed sizes in the video's true
     * aspect ratio (it scales width to match). */
    private static final int PLAYER_PROBE_HEIGHT = 720;

    private final WebClient.Builder webClientBuilder;
    private final YouTubeProperties properties;

    public YouTubeDataClient(WebClient.Builder webClientBuilder, YouTubeProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }

    public record ChannelInfo(String channelId, String title, String description, String thumbnailUrl,
                              String uploadsPlaylistId) {
    }

    public record PlaylistPage(List<String> videoIds, String nextPageToken) {
    }

    /** {@code description} is read only to look for our "Made on Dalaillama" marker when a creator
     * links a platform film; it is never stored. */
    public record VideoInfo(String videoId, String channelId, String title, String description, String thumbnailUrl,
                            Instant publishedAt, Duration duration, String privacyStatus, boolean embeddable,
                            boolean madeForKids, boolean ageRestricted, Integer embedWidth, Integer embedHeight) {
    }

    public Optional<ChannelInfo> findChannel(ChannelRef ref) {
        JsonNode body = get("/channels", b -> {
            b.queryParam("part", "snippet,contentDetails");
            // Java 17: no pattern-matching switch yet.
            if (ref instanceof ChannelRef.ById id) return b.queryParam("id", id.channelId());
            if (ref instanceof ChannelRef.ByHandle h) return b.queryParam("forHandle", h.handle());
            return b.queryParam("forUsername", ((ChannelRef.ByUsername) ref).username());
        });
        JsonNode item = body.path("items").path(0);
        if (item.isMissingNode()) return Optional.empty();
        JsonNode snippet = item.path("snippet");
        return Optional.of(new ChannelInfo(
                item.path("id").asText(),
                snippet.path("title").asText(null),
                snippet.path("description").asText(""),
                bestThumbnail(snippet.path("thumbnails")),
                item.path("contentDetails").path("relatedPlaylists").path("uploads").asText()));
    }

    public PlaylistPage playlistItems(String playlistId, String pageToken) {
        JsonNode body = get("/playlistItems", b -> {
            b.queryParam("part", "contentDetails")
                    .queryParam("playlistId", playlistId)
                    .queryParam("maxResults", MAX_IDS_PER_CALL);
            return pageToken == null ? b : b.queryParam("pageToken", pageToken);
        });
        List<String> ids = new ArrayList<>();
        body.path("items").forEach(i -> ids.add(i.path("contentDetails").path("videoId").asText()));
        String next = body.path("nextPageToken").asText(null);
        return new PlaylistPage(ids, next);
    }

    /** Up to 50 videos per call. Videos YouTube no longer returns (deleted, private) are simply
     * absent from the result. */
    public List<VideoInfo> videos(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > MAX_IDS_PER_CALL) throw new IllegalArgumentException("At most 50 ids per call");
        JsonNode body = get("/videos", b -> b
                .queryParam("part", "snippet,contentDetails,status,player")
                .queryParam("id", String.join(",", ids))
                .queryParam("maxHeight", PLAYER_PROBE_HEIGHT));
        List<VideoInfo> videos = new ArrayList<>();
        body.path("items").forEach(item -> videos.add(toVideo(item)));
        return videos;
    }

    private VideoInfo toVideo(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode status = item.path("status");
        JsonNode details = item.path("contentDetails");
        JsonNode player = item.path("player");
        String published = snippet.path("publishedAt").asText(null);
        String duration = details.path("duration").asText(null);
        return new VideoInfo(
                item.path("id").asText(),
                snippet.path("channelId").asText(),
                snippet.path("title").asText(null),
                snippet.path("description").asText(""),
                bestThumbnail(snippet.path("thumbnails")),
                published == null ? null : Instant.parse(published),
                parseDuration(duration),
                status.path("privacyStatus").asText(null),
                status.path("embeddable").asBoolean(false),
                status.path("madeForKids").asBoolean(false),
                "ytAgeRestricted".equals(details.path("contentRating").path("ytRating").asText(null)),
                positiveInt(player.path("embedWidth")),
                positiveInt(player.path("embedHeight")));
    }

    private JsonNode get(String path, Function<UriBuilder, UriBuilder> query) {
        if (!properties.configured()) {
            throw new YouTubeUnavailableException("YouTube is not set up yet; please try again later");
        }
        try {
            JsonNode body = webClientBuilder.baseUrl(properties.baseUrl()).build()
                    .get()
                    .uri(b -> query.apply(b.path(path)).queryParam("key", properties.apiKey()).build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (body == null) throw new YouTubeUnavailableException("YouTube returned an empty response");
            return body;
        } catch (YouTubeUnavailableException e) {
            throw e;
        } catch (WebClientResponseException e) {
            // 403 here is almost always quota or a key restriction, never the creator's fault.
            log.error("YouTube {} failed: status={} body={}", path, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new YouTubeUnavailableException("YouTube is not answering right now; please try again later", e);
        } catch (RuntimeException e) {
            log.error("YouTube {} failed: {}", path, e.getMessage(), e);
            throw new YouTubeUnavailableException("YouTube is not answering right now; please try again later", e);
        }
    }

    private static String bestThumbnail(JsonNode thumbnails) {
        for (String size : List.of("maxres", "standard", "high", "medium", "default")) {
            String url = thumbnails.path(size).path("url").asText(null);
            if (url != null) return url;
        }
        return null;
    }

    private static Duration parseDuration(String iso) {
        if (iso == null) return null;
        try {
            return Duration.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer positiveInt(JsonNode node) {
        int value = node.asInt(0); // YouTube sends these int64 values as strings; asInt handles both
        return value > 0 ? value : null;
    }
}
