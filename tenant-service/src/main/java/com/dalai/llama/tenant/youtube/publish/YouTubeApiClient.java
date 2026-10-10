package com.dalai.llama.tenant.youtube.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The YouTube calls publishing needs, with a creator's (or our) access token (rules 35–36):
 * resumable upload (start, ask for the committed offset, send one chunk), thumbnail, metadata
 * update. Every failure is classified so the worker knows whether to retry, refresh, or stop. */
@Component
public class YouTubeApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CHUNK_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern RANGE = Pattern.compile("bytes=0-(\\d+)");

    private final ObjectMapper json;
    private final String dataUrl;
    private final String uploadUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public YouTubeApiClient(ObjectMapper json,
                            @Value("${youtube.base-url:https://www.googleapis.com/youtube/v3}") String dataUrl,
                            @Value("${youtube.upload-base-url:https://www.googleapis.com/upload/youtube/v3}") String uploadUrl) {
        this.json = json;
        this.dataUrl = dataUrl;
        this.uploadUrl = uploadUrl;
    }

    /** Why a call failed, and what the caller should do about it. */
    public enum Failure {
        /** Token rejected: refresh once, then the connection needs reconnecting. */
        UNAUTHORIZED,
        /** Daily quota or upload limit: stop; a person retries later. */
        QUOTA,
        /** The upload session is gone (expired or unknown): start a new one. */
        SESSION_GONE,
        /** 5xx, network: retry with backoff. */
        TRANSIENT,
        /** Any other refusal (bad metadata, video too long...): stop with YouTube's message. */
        REJECTED
    }

    public static class YouTubeCallException extends RuntimeException {
        private final Failure failure;

        public YouTubeCallException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    public record Metadata(String title, String description, List<String> tags, String categoryId, String privacy, Instant publishAt) {
    }

    /** Where an upload stands: done (with the video), or the next byte YouTube wants. */
    public record UploadState(String videoId, String privacyStatus, long nextOffset) {
        public boolean done() {
            return videoId != null;
        }
    }

    public String startUpload(String token, Metadata metadata, long length) {
        HttpResponse<String> init = send(HttpRequest.newBuilder(URI.create(uploadUrl + "/videos?uploadType=resumable&part=snippet,status"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "video/mp4")
                .header("X-Upload-Content-Length", Long.toString(length))
                .POST(HttpRequest.BodyPublishers.ofString(write(videoResource(null, metadata))))
                .build());
        if (init.statusCode() != 200) throw failure(init);
        return init.headers().firstValue("Location")
                .orElseThrow(() -> new YouTubeCallException(Failure.TRANSIENT, "YouTube did not return an upload session"));
    }

    /** Asks the session how much it already has (resume after a crash or a failed chunk). */
    public UploadState status(String token, String session, long total) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(session)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Range", "bytes */" + total)
                .PUT(HttpRequest.BodyPublishers.noBody()).build());
        return state(r);
    }

    /** Sends bytes [offset, offset + length) read from {@code body}; streams, never buffers. */
    public UploadState sendChunk(String token, String session, InputStream body, long offset, long length, long total) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(session)).timeout(CHUNK_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "video/mp4")
                .header("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + total)
                .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body), length))
                .build());
        return state(r);
    }

    public void setThumbnail(String token, String videoId, byte[] image, String contentType) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(uploadUrl + "/thumbnails/set?videoId=" + videoId))
                .timeout(TIMEOUT.multipliedBy(2))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build());
        if (r.statusCode() != 200) throw failure(r);
    }

    /** Rule 36: title, description, tags, category, visibility and schedule in one update. */
    public String updateVideo(String token, String videoId, Metadata metadata) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(dataUrl + "/videos?part=snippet,status"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(write(videoResource(videoId, metadata)))).build());
        if (r.statusCode() != 200) throw failure(r);
        return read(r.body()).path("status").path("privacyStatus").asText(null);
    }

    private UploadState state(HttpResponse<String> r) {
        if (r.statusCode() == 200 || r.statusCode() == 201) {
            JsonNode video = read(r.body());
            return new UploadState(video.path("id").asText(), video.path("status").path("privacyStatus").asText(null), -1);
        }
        if (r.statusCode() == 308) {
            Optional<String> range = r.headers().firstValue("Range");
            if (range.isEmpty()) return new UploadState(null, null, 0);
            Matcher m = RANGE.matcher(range.get());
            return new UploadState(null, null, m.find() ? Long.parseLong(m.group(1)) + 1 : 0);
        }
        throw failure(r);
    }

    private Map<String, Object> videoResource(String id, Metadata m) {
        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("title", m.title());
        snippet.put("description", m.description());
        snippet.put("tags", m.tags());
        snippet.put("categoryId", m.categoryId());
        Map<String, Object> status = new LinkedHashMap<>();
        // A schedule needs "private" now; YouTube makes it public at publishAt.
        status.put("privacyStatus", m.publishAt() != null ? "private" : m.privacy());
        if (m.publishAt() != null) status.put("publishAt", m.publishAt().toString());
        status.put("selfDeclaredMadeForKids", false);
        // YouTube's disclosure for realistic AI-made video; always true for our films.
        status.put("containsSyntheticMedia", true);
        Map<String, Object> resource = new LinkedHashMap<>();
        if (id != null) resource.put("id", id);
        resource.put("snippet", snippet);
        resource.put("status", status);
        return resource;
    }

    private YouTubeCallException failure(HttpResponse<String> r) {
        int code = r.statusCode();
        JsonNode error = read(r.body()).path("error");
        List<String> reasons = new ArrayList<>();
        error.path("errors").forEach(e -> reasons.add(e.path("reason").asText()));
        String message = error.path("message").asText("HTTP " + code);
        if (code == 401) return new YouTubeCallException(Failure.UNAUTHORIZED, message);
        if (code == 403 && reasons.stream().anyMatch(x -> x.contains("quota") || x.contains("uploadLimitExceeded") || x.contains("rateLimit"))) {
            return new YouTubeCallException(Failure.QUOTA, message);
        }
        if (code == 404 || code == 410) return new YouTubeCallException(Failure.SESSION_GONE, message);
        if (code >= 500 || code == 429) return new YouTubeCallException(Failure.TRANSIENT, message);
        return new YouTubeCallException(Failure.REJECTED, message);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new YouTubeCallException(Failure.TRANSIENT, "YouTube could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeCallException(Failure.TRANSIENT, "Interrupted");
        }
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
