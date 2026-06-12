package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class GoogleGenAiClientFactory {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final CreatorProperties properties;
    private final WebClient.Builder webClientBuilder;

    public GoogleGenAiClientFactory(CreatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public WebClient client(int maxInMemoryBytes) {
        WebClient.Builder builder = webClientBuilder
                .baseUrl(baseUrl())
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemoryBytes))
                        .build())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (useVertexAi()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken());
        } else {
            String apiKey = properties.getAi().getGeminiApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Gemini API key is not configured for AI Studio. Set GEMINI_API_KEY or switch CREATOR_GOOGLE_GENAI_BACKEND=vertex.");
            }
            builder.defaultHeader("x-goog-api-key", apiKey);
        }
        return builder.build();
    }

    public String generateContentUri(String model) {
        if (!useVertexAi()) {
            return "/models/%s:generateContent".formatted(model);
        }
        return "/projects/%s/locations/%s/publishers/google/models/%s:generateContent".formatted(
                projectId(),
                location(),
                model
        );
    }

    public String predictLongRunningUri(String model) {
        if (!useVertexAi()) {
            return "/models/%s:predictLongRunning".formatted(model);
        }
        return "/projects/%s/locations/%s/publishers/google/models/%s:predictLongRunning".formatted(
                projectId(),
                location(),
                model
        );
    }

    public URI operationUri(String operationName) {
        String normalized = stringValue(operationName, "").replaceAll("^/+", "");
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return URI.create(normalized);
        }
        return URI.create(baseUrl().replaceAll("/+$", "") + "/" + normalized);
    }

    public String backend() {
        return useVertexAi() ? "vertex" : "ai_studio";
    }

    public boolean useVertexAi() {
        String backend = stringValue(properties.getAi().getGoogleGenaiBackend(), "ai_studio")
                .trim()
                .toLowerCase()
                .replace("-", "_");
        return "vertex".equals(backend) || "vertex_ai".equals(backend);
    }

    public String baseUrl() {
        if (!useVertexAi()) {
            return stringValue(properties.getAi().getGeminiBaseUrl(), "https://generativelanguage.googleapis.com/v1beta");
        }
        return "https://%s-aiplatform.googleapis.com/v1".formatted(location());
    }

    private String projectId() {
        String projectId = stringValue(properties.getAi().getGoogleCloudProjectId(), "");
        if (projectId.isBlank()) {
            throw new IllegalStateException("Google Cloud project is not configured for Vertex AI. Set GOOGLE_CLOUD_PROJECT_ID.");
        }
        return projectId;
    }

    private String location() {
        return stringValue(properties.getAi().getGoogleCloudLocation(), "us-central1");
    }

    private String accessToken() {
        try {
            GoogleCredentials credentials = googleCredentials().createScoped(List.of(CLOUD_PLATFORM_SCOPE));
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                credentials.refresh();
                token = credentials.getAccessToken();
            }
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google application credentials did not return an access token.");
            }
            return token.getTokenValue();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load Google application credentials for Vertex AI.", ex);
        }
    }

    private GoogleCredentials googleCredentials() throws IOException {
        String credentialsJson = stringValue(System.getenv("GOOGLE_CREDENTIALS_JSON"), "");
        if (!credentialsJson.isBlank()) {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private String stringValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
