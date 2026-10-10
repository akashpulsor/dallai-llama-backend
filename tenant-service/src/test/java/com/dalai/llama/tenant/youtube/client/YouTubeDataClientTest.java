package com.dalai.llama.tenant.youtube.client;

import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real client against a local HTTP server that answers with recorded YouTube Data API
 * responses, so the request shape and the JSON mapping are both checked at the HTTP boundary. */
class YouTubeDataClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private YouTubeDataClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        client = new YouTubeDataClient(WebClient.builder(), properties("test-key"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void findChannel_byHandle_mapsTheUploadsPlaylistAndBestThumbnail() {
        body = """
                {"items":[{"id":"UC123","snippet":{"title":"Riya Motion","description":"Films. dalai-ABC234",
                  "thumbnails":{"default":{"url":"https://yt3/d.jpg"},"high":{"url":"https://yt3/h.jpg"}}},
                  "contentDetails":{"relatedPlaylists":{"uploads":"UU123"}}}]}""";

        YouTubeDataClient.ChannelInfo channel = client.findChannel(new ChannelRef.ByHandle("@RiyaMotion")).orElseThrow();

        assertThat(channel.channelId()).isEqualTo("UC123");
        assertThat(channel.uploadsPlaylistId()).isEqualTo("UU123");
        assertThat(channel.thumbnailUrl()).isEqualTo("https://yt3/h.jpg");
        assertThat(channel.description()).contains("dalai-ABC234");
        assertThat(requests.get(0)).contains("/channels").contains("forHandle=@RiyaMotion").contains("key=test-key");
    }

    @Test
    void findChannel_isEmptyWhenYouTubeHasNoSuchChannel() {
        body = "{\"pageInfo\":{\"totalResults\":0}}";
        assertThat(client.findChannel(new ChannelRef.ById("UCnope"))).isEmpty();
    }

    @Test
    void playlistItems_returnsIdsAndTheNextPageToken() {
        body = """
                {"nextPageToken":"P2","items":[{"contentDetails":{"videoId":"v1"}},{"contentDetails":{"videoId":"v2"}}]}""";

        YouTubeDataClient.PlaylistPage page = client.playlistItems("UU123", null);

        assertThat(page.videoIds()).containsExactly("v1", "v2");
        assertThat(page.nextPageToken()).isEqualTo("P2");
        assertThat(requests.get(0)).contains("playlistId=UU123").contains("maxResults=50").doesNotContain("pageToken");
    }

    @Test
    void videos_mapsFactsIncludingStringEmbedSizesAndAgeRestriction() {
        body = """
                {"items":[
                  {"id":"short1","snippet":{"channelId":"UC123","title":"Chai in 15s","publishedAt":"2026-09-01T10:00:00Z",
                     "thumbnails":{"medium":{"url":"https://i.ytimg/m.jpg"}}},
                   "contentDetails":{"duration":"PT15S"},
                   "status":{"privacyStatus":"public","embeddable":true,"madeForKids":false},
                   "player":{"embedHtml":"<iframe/>","embedWidth":"405","embedHeight":"720"}},
                  {"id":"long1","snippet":{"channelId":"UC123","title":"Brand film","publishedAt":"2026-08-01T10:00:00Z","thumbnails":{}},
                   "contentDetails":{"duration":"PT1M30S","contentRating":{"ytRating":"ytAgeRestricted"}},
                   "status":{"privacyStatus":"public","embeddable":false,"madeForKids":true},
                   "player":{"embedWidth":1280,"embedHeight":720}}]}""";

        List<YouTubeDataClient.VideoInfo> videos = client.videos(List.of("short1", "long1"));

        YouTubeDataClient.VideoInfo vertical = videos.get(0);
        assertThat(vertical.embedWidth()).isEqualTo(405);
        assertThat(vertical.embedHeight()).isEqualTo(720);
        assertThat(vertical.duration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(vertical.publishedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(vertical.thumbnailUrl()).isEqualTo("https://i.ytimg/m.jpg");
        assertThat(vertical.embeddable()).isTrue();
        assertThat(vertical.ageRestricted()).isFalse();

        YouTubeDataClient.VideoInfo restricted = videos.get(1);
        assertThat(restricted.ageRestricted()).isTrue();
        assertThat(restricted.madeForKids()).isTrue();
        assertThat(restricted.embeddable()).isFalse();
        assertThat(restricted.thumbnailUrl()).isNull();
        assertThat(requests.get(0)).contains("id=short1,long1").contains("maxHeight=720");
    }

    @Test
    void googleErrorsAndAMissingKeyBecomeUnavailable() {
        status = 403;
        body = "{\"error\":{\"code\":403,\"message\":\"quotaExceeded\"}}";
        assertThatThrownBy(() -> client.playlistItems("UU1", null)).isInstanceOf(YouTubeUnavailableException.class);

        YouTubeDataClient noKey = new YouTubeDataClient(WebClient.builder(), properties(""));
        assertThatThrownBy(() -> noKey.findChannel(new ChannelRef.ById("UC1")))
                .isInstanceOf(YouTubeUnavailableException.class)
                .hasMessageContaining("not set up");
    }

    private YouTubeProperties properties(String key) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
        return new YouTubeProperties(key, base, 500, 25, 500, new YouTubeProperties.Eligibility(5, 600));
    }
}
