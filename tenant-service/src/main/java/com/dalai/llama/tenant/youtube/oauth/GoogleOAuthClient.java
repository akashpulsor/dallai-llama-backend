package com.dalai.llama.tenant.youtube.oauth;

import com.dalai.llama.tenant.youtube.client.YouTubeUnavailableException;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Google's token endpoint (code exchange, refresh, revoke) and the one YouTube read the connect
 * flow needs: "which channel did this sign-in grant". Plain HTTP so tests can stand in a stub. */
@Slf4j
@Component
public class GoogleOAuthClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final GoogleOAuthProperties properties;
    private final YouTubeProperties youTube;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public GoogleOAuthClient(GoogleOAuthProperties properties, YouTubeProperties youTube, ObjectMapper json) {
        this.properties = properties;
        this.youTube = youTube;
        this.json = json;
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds, String scope) {
    }

    public record AccessToken(String value, long expiresInSeconds) {
    }

    public record Channel(String channelId, String title, String thumbnailUrl, String uploadsPlaylistId) {
    }

    /** The refresh token was revoked or expired (Testing-mode tokens last 7 days): reconnect. */
    public static class InvalidGrantException extends RuntimeException {
        public InvalidGrantException(String message) {
            super(message);
        }
    }

    public Tokens exchangeCode(String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("redirect_uri", properties.redirectUri());
        form.put("grant_type", "authorization_code");
        form.put("code_verifier", verifier);
        JsonNode body = tokenCall(form);
        return new Tokens(body.path("access_token").asText(), body.path("refresh_token").asText(null),
                body.path("expires_in").asLong(3600), body.path("scope").asText(""));
    }

    public AccessToken refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        JsonNode body = tokenCall(form);
        return new AccessToken(body.path("access_token").asText(), body.path("expires_in").asLong(3600));
    }

    /** Best effort: a token Google already dropped is fine. */
    public void revoke(String token) {
        try {
            http.send(HttpRequest.newBuilder(URI.create(properties.revokeUrl()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.warn("Token revoke failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The channel the signed-in Google account granted ({@code channels.list?mine=true}). */
    public Channel myChannel(String accessToken) {
        JsonNode item = getJson(youTube.baseUrl() + "/channels?part=snippet,contentDetails&mine=true", accessToken)
                .path("items").path(0);
        if (item.isMissingNode()) {
            throw new IllegalStateException("That Google account has no YouTube channel. Create one on YouTube, then connect again.");
        }
        JsonNode snippet = item.path("snippet");
        JsonNode thumbs = snippet.path("thumbnails");
        String thumb = thumbs.path("high").path("url").asText(thumbs.path("default").path("url").asText(null));
        return new Channel(item.path("id").asText(), snippet.path("title").asText(null), thumb,
                item.path("contentDetails").path("relatedPlaylists").path("uploads").asText(null));
    }

    /** An authorised GET to a Google API, for reads that need the channel owner's token. */
    public JsonNode getJson(String url, String accessToken) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new YouTubeUnavailableException("YouTube answered HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new YouTubeUnavailableException("YouTube could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeUnavailableException("Interrupted calling YouTube", e);
        }
    }

    private JsonNode tokenCall(Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(properties.tokenUrl()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = json.readTree(response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() == 400 && "invalid_grant".equals(parsed.path("error").asText())) {
                throw new InvalidGrantException(parsed.path("error_description").asText("Access was revoked"));
            }
            if (response.statusCode() != 200) {
                throw new YouTubeUnavailableException("Google sign-in answered HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            return parsed;
        } catch (IOException e) {
            throw new YouTubeUnavailableException("Google sign-in could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeUnavailableException("Interrupted calling Google", e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
