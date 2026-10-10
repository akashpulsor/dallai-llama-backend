package com.dalai.llama.tenant.youtube.client;

import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Uploads a film to Dalaillama's OWN YouTube channel (CREATOR_SHOWCASE.md rule 3). Uses the one
 * refresh token ops created through our Internal-consent OAuth client; never a creator's account.
 * The film is streamed from its presigned MinIO link straight into YouTube's resumable upload, so
 * it never sits in memory or on disk here. */
@Slf4j
@Component
public class YouTubeUploadClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(30);
    /** "People & Blogs"; YouTube needs some category and this is the neutral one. */
    private static final String CATEGORY_ID = "22";

    private final VideoHostProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public YouTubeUploadClient(VideoHostProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public record UploadRequest(String title, String description, List<String> tags, String sourceUrl) {
    }

    /** {@code privacyStatus} is what YouTube actually applied; if it differs from what we asked
     * for, YouTube restricted the upload and a person has to step in. */
    public record UploadResult(String videoId, String privacyStatus) {
    }

    public synchronized UploadResult upload(UploadRequest request) {
        VideoHostProperties.OfficialChannel channel = properties.officialChannel();
        if (!channel.configured()) throw new YouTubeUnavailableException("Dalaillama's YouTube channel is not connected yet");
        try {
            HttpResponse<InputStream> film = http.send(HttpRequest.newBuilder(URI.create(request.sourceUrl()))
                    .timeout(UPLOAD_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (film.statusCode() != 200) {
                film.body().close();
                throw new YouTubeUnavailableException("The film could not be read for upload (HTTP " + film.statusCode() + ")");
            }
            long length = film.headers().firstValueAsLong("Content-Length")
                    .orElseThrow(() -> new YouTubeUnavailableException("The film's size is unknown; cannot start the upload"));

            String session = startSession(channel, request, length);
            HttpResponse<String> done = http.send(HttpRequest.newBuilder(URI.create(session))
                    .timeout(UPLOAD_TIMEOUT)
                    .header("Authorization", "Bearer " + token(channel))
                    .header("Content-Type", "video/mp4")
                    .PUT(HttpRequest.BodyPublishers.fromPublisher(
                            HttpRequest.BodyPublishers.ofInputStream(film::body), length))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (done.statusCode() != 200 && done.statusCode() != 201) {
                throw new YouTubeUnavailableException("YouTube refused the upload (HTTP " + done.statusCode() + "): " + done.body());
            }
            JsonNode video = json.readTree(done.body());
            return new UploadResult(video.path("id").asText(), video.path("status").path("privacyStatus").asText(null));
        } catch (IOException e) {
            throw new YouTubeUnavailableException("Upload to YouTube failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeUnavailableException("Upload to YouTube was interrupted", e);
        }
    }

    private String startSession(VideoHostProperties.OfficialChannel channel, UploadRequest request, long length)
            throws IOException, InterruptedException {
        Map<String, Object> metadata = Map.of(
                "snippet", Map.of(
                        "title", request.title(),
                        "description", request.description(),
                        "tags", request.tags(),
                        "categoryId", CATEGORY_ID),
                "status", Map.of(
                        "privacyStatus", channel.privacy(),
                        "selfDeclaredMadeForKids", false,
                        // YouTube's disclosure for realistic AI-made video; always true for our films.
                        "containsSyntheticMedia", true));
        HttpResponse<String> init = http.send(HttpRequest.newBuilder(
                        URI.create(channel.uploadUrl() + "?uploadType=resumable&part=snippet,status"))
                .timeout(CONNECT_TIMEOUT.multipliedBy(3))
                .header("Authorization", "Bearer " + token(channel))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "video/mp4")
                .header("X-Upload-Content-Length", Long.toString(length))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(metadata)))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (init.statusCode() != 200) {
            throw new YouTubeUnavailableException("YouTube refused to start the upload (HTTP " + init.statusCode() + "): " + init.body());
        }
        return init.headers().firstValue("Location")
                .orElseThrow(() -> new YouTubeUnavailableException("YouTube did not return an upload session"));
    }

    private String token(VideoHostProperties.OfficialChannel channel) throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt)) return accessToken;
        String form = Map.of(
                        "client_id", channel.clientId(),
                        "client_secret", channel.clientSecret(),
                        "refresh_token", channel.refreshToken(),
                        "grant_type", "refresh_token")
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(channel.tokenUrl()))
                .timeout(CONNECT_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Official channel token refresh failed: {} {}", response.statusCode(), response.body());
            throw new YouTubeUnavailableException("Dalaillama's YouTube access was refused; ops must reconnect the channel");
        }
        JsonNode body = json.readTree(response.body());
        accessToken = body.path("access_token").asText();
        accessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, body.path("expires_in").asLong(3600) - 60));
        return accessToken;
    }
}
