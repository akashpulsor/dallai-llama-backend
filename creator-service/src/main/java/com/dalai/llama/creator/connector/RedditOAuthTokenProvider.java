package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Component
public class RedditOAuthTokenProvider {

    private final CreatorProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private volatile CachedToken cachedToken;

    public RedditOAuthTokenProvider(
            CreatorProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean hasCredentials() {
        return properties.getConnectors().getReddit().hasCredentials();
    }

    public String getAccessToken() {
        CachedToken token = cachedToken;
        if (token != null && !token.isExpiringSoon(properties.getConnectors().getReddit().getTokenRefreshSkewSeconds())) {
            return token.accessToken();
        }

        synchronized (this) {
            token = cachedToken;
            if (token != null && !token.isExpiringSoon(properties.getConnectors().getReddit().getTokenRefreshSkewSeconds())) {
                return token.accessToken();
            }
            cachedToken = fetchToken();
            return cachedToken.accessToken();
        }
    }

    public synchronized void invalidate() {
        cachedToken = null;
    }

    private CachedToken fetchToken() {
        CreatorProperties.Reddit reddit = properties.getConnectors().getReddit();
        if (!reddit.hasCredentials()) {
            throw new IllegalStateException("Reddit OAuth credentials are not configured. Set REDDIT_CLIENT_ID and REDDIT_CLIENT_SECRET.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        try {
            String body = webClient.post()
                    .uri(reddit.getTokenUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth(reddit.getClientId(), reddit.getClientSecret()))
                    .header(HttpHeaders.USER_AGENT, reddit.getUserAgent())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            JsonNode root = objectMapper.readTree(body);
            String accessToken = root.path("access_token").asText(null);
            long expiresIn = root.path("expires_in").asLong(3600);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Reddit OAuth token response did not contain access_token");
            }
            return new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fetch Reddit OAuth token", ex);
        }
    }

    private String basicAuth(String clientId, String clientSecret) {
        String value = clientId + ":" + clientSecret;
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        private boolean isExpiringSoon(long skewSeconds) {
            return Instant.now().plusSeconds(Math.max(0, skewSeconds)).isAfter(expiresAt);
        }
    }
}
