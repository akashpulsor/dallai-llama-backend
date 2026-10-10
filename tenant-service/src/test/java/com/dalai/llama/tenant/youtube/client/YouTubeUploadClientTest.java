package com.dalai.llama.tenant.youtube.client;

import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The official-channel upload against a local server playing Google's token endpoint, the
 * resumable-upload endpoints and our MinIO film link. */
class YouTubeUploadClientTest {

    private static final byte[] FILM = "not really an mp4, but bytes are bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private volatile String appliedPrivacy = "public";
    private volatile int tokenStatus = 200;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            seen.put("tokenForm", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, tokenStatus, "{\"access_token\":\"at-1\",\"expires_in\":3600}", Map.of());
        });
        server.createContext("/film.mp4", ex -> {
            ex.sendResponseHeaders(200, FILM.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(FILM);
            }
        });
        server.createContext("/upload", ex -> {
            seen.put("initQuery", ex.getRequestURI().getQuery());
            seen.put("initAuth", ex.getRequestHeaders().getFirst("Authorization"));
            seen.put("initLength", ex.getRequestHeaders().getFirst("X-Upload-Content-Length"));
            seen.put("metadata", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "", Map.of("Location", base() + "/session/abc"));
        });
        server.createContext("/session/abc", ex -> {
            seen.put("putMethod", ex.getRequestMethod());
            seen.put("putBody", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"id\":\"newVideo01\",\"status\":{\"privacyStatus\":\"" + appliedPrivacy + "\"}}", Map.of());
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void streamsTheFilmIntoAResumableUploadWithTheAiDisclosure() throws Exception {
        YouTubeUploadClient client = new YouTubeUploadClient(properties(true), json);

        YouTubeUploadClient.UploadResult result = client.upload(request());

        assertThat(result.videoId()).isEqualTo("newVideo01");
        assertThat(result.privacyStatus()).isEqualTo("public");
        assertThat(seen.get("tokenForm")).contains("grant_type=refresh_token").contains("refresh_token=rt-1");
        assertThat(seen.get("initQuery")).contains("uploadType=resumable").contains("part=snippet,status");
        assertThat(seen.get("initAuth")).isEqualTo("Bearer at-1");
        assertThat(seen.get("initLength")).isEqualTo(Integer.toString(FILM.length));
        JsonNode metadata = json.readTree(seen.get("metadata"));
        assertThat(metadata.path("status").path("containsSyntheticMedia").asBoolean()).isTrue();
        assertThat(metadata.path("status").path("selfDeclaredMadeForKids").asBoolean()).isFalse();
        assertThat(metadata.path("status").path("privacyStatus").asText()).isEqualTo("public");
        assertThat(metadata.path("snippet").path("title").asText()).isEqualTo("Café chain · Made by Riya on Dalaillama");
        assertThat(seen.get("putMethod")).isEqualTo("PUT");
        assertThat(seen.get("putBody").getBytes(StandardCharsets.UTF_8)).isEqualTo(FILM);

        // The access token is reused until it nearly expires.
        client.upload(request());
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void reportsThePrivacyYouTubeActuallyApplied() {
        appliedPrivacy = "private";
        assertThat(new YouTubeUploadClient(properties(true), json).upload(request()).privacyStatus()).isEqualTo("private");
    }

    @Test
    void refusesWhenNotConnectedOrWhenGoogleRefusesTheToken() {
        assertThatThrownBy(() -> new YouTubeUploadClient(properties(false), json).upload(request()))
                .isInstanceOf(YouTubeUnavailableException.class).hasMessageContaining("not connected");
        tokenStatus = 400;
        assertThatThrownBy(() -> new YouTubeUploadClient(properties(true), json).upload(request()))
                .isInstanceOf(YouTubeUnavailableException.class).hasMessageContaining("reconnect");
    }

    private YouTubeUploadClient.UploadRequest request() {
        return new YouTubeUploadClient.UploadRequest("Café chain · Made by Riya on Dalaillama",
                "Made by Riya on Dalaillama · https://dalaillama.in/c/riya", List.of("dalaillama", "ai video"),
                base() + "/film.mp4");
    }

    private VideoHostProperties properties(boolean enabled) {
        return new VideoHostProperties(VideoHostType.YOUTUBE, new VideoHostProperties.OfficialChannel(
                enabled, "client-1", "secret-1", "rt-1", base() + "/token", base() + "/upload", "public",
                "Made by {creatorName} on Dalaillama · {profileUrl}", true));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int status, String body, Map<String, String> headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
