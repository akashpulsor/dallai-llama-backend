package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GoogleLyriaMusicGenerationService {

    private static final Logger log = LoggerFactory.getLogger(GoogleLyriaMusicGenerationService.class);
    private static final String DEFAULT_LYRIA_MODEL = "lyria-3-clip-preview";
    private static final String DEFAULT_LOCATION = "us-central1";
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final int AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES = 96 * 1024 * 1024;

    private final CreatorProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final CreatorAiPricingService pricingService;

    public GoogleLyriaMusicGenerationService(
            CreatorProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            CreatorAiPricingService pricingService
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.pricingService = pricingService;
    }

    public GeneratedAudio generateMusic(String prompt, String negativePrompt, String layerType, double durationSeconds) {
        String normalizedLayerType = normalizeLayerType(layerType);
        if ("dialogue".equals(normalizedLayerType) || "voice".equals(normalizedLayerType) || "spoken".equals(normalizedLayerType)) {
            throw new IllegalArgumentException("Lyria cannot be used for dialogue, voice, or speech cleanup. Use the audio enhancement path.");
        }
        if (!properties.getAi().isLyriaMusicGenerationEnabled()) {
            throw new IllegalStateException("Lyria music generation is disabled. Set LYRIA_MUSIC_GENERATION_ENABLED=true.");
        }
        String projectId = stringValue(properties.getAi().getGoogleCloudProjectId(), "");
        if (projectId.isBlank()) {
            throw new IllegalStateException("Google Cloud project is not configured. Set GOOGLE_CLOUD_PROJECT_ID for Lyria.");
        }
        String model = stringValue(properties.getAi().getLyriaMusicModel(), DEFAULT_LYRIA_MODEL);
        String location = stringValue(properties.getAi().getGoogleCloudLocation(), DEFAULT_LOCATION);
        String token = accessToken();
        String normalizedPrompt = buildPrompt(prompt, negativePrompt, normalizedLayerType, durationSeconds);
        JsonNode response;
        Map<String, Object> request;
        InlineAudio audio;

        log.info("Google Lyria music generation request model={} projectId={} location={} layerType={}", model, projectId, location, normalizedLayerType);
        if (model.startsWith("lyria-3")) {
            request = lyria3Request(model, normalizedPrompt);
            response = vertexClient("https://aiplatform.googleapis.com", token)
                    .post()
                    .uri("/v1beta1/projects/{projectId}/locations/global/interactions", projectId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 180000)));
            audio = extractLyria3Audio(response, model);
        } else {
            request = lyria2Request(normalizedPrompt, negativePrompt);
            response = vertexClient("https://%s-aiplatform.googleapis.com".formatted(location), token)
                    .post()
                    .uri("/v1/projects/{projectId}/locations/{location}/publishers/google/models/{model}:predict", projectId, location, model)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 180000)));
            audio = extractLyria2Audio(response, model);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "google_lyria");
        metadata.put("model", model);
        metadata.put("projectId", projectId);
        metadata.put("location", model.startsWith("lyria-3") ? "global" : location);
        metadata.put("layerType", normalizedLayerType);
        metadata.put("requestedDurationSeconds", durationSeconds);
        metadata.put("mimeType", audio.mimeType());
        metadata.put("responsePath", audio.responsePath());
        metadata.put("costMetadata", pricingService.estimateLyriaCall(
                model,
                BigDecimal.valueOf(Math.max(0.25, durationSeconds)),
                1,
                "lyria_music_generation"
        ));
        return new GeneratedAudio(
                decodeAudio(audio.base64Data()),
                stringValue(audio.mimeType(), "audio/wav"),
                metadata,
                request,
                response == null ? Map.of() : objectMapper.convertValue(response, new TypeReference<>() {})
        );
    }

    private WebClient vertexClient(String baseUrl, String accessToken) {
        return webClientBuilder
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
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
            throw new IllegalStateException("Could not load Google application credentials for Vertex AI Lyria.", ex);
        }
    }

    private GoogleCredentials googleCredentials() throws IOException {
        String credentialsJson = stringValue(System.getenv("GOOGLE_CREDENTIALS_JSON"), "");
        if (!credentialsJson.isBlank()) {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private Map<String, Object> lyria3Request(String model, String prompt) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", prompt);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", List.of(text));
        return request;
    }

    private Map<String, Object> lyria2Request(String prompt, String negativePrompt) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("prompt", prompt);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            instance.put("negative_prompt", negativePrompt);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("instances", List.of(instance));
        request.put("parameters", Map.of("sample_count", 1));
        return request;
    }

    private InlineAudio extractLyria3Audio(JsonNode response, String model) {
        JsonNode outputs = response == null ? null : response.path("outputs");
        if (outputs != null && outputs.isArray()) {
            for (int index = 0; index < outputs.size(); index++) {
                JsonNode output = outputs.get(index);
                String type = textAt(output, "type");
                String mimeType = firstText(textAt(output, "mime_type"), textAt(output, "mimeType"), "audio/mpeg");
                String data = firstText(textAt(output, "data"), textAt(output, "audioContent"), "");
                if (!data.isBlank() && ("audio".equalsIgnoreCase(type) || mimeType.startsWith("audio/"))) {
                    return new InlineAudio(mimeType, data, "outputs[%d].data".formatted(index));
                }
            }
        }
        throw new IllegalStateException("Google Lyria 3 returned no audio output for model " + model + ".");
    }

    private InlineAudio extractLyria2Audio(JsonNode response, String model) {
        JsonNode predictions = response == null ? null : response.path("predictions");
        if (predictions != null && predictions.isArray()) {
            for (int index = 0; index < predictions.size(); index++) {
                JsonNode prediction = predictions.get(index);
                String data = textAt(prediction, "audioContent");
                if (!data.isBlank()) {
                    return new InlineAudio(
                            firstText(textAt(prediction, "mimeType"), textAt(prediction, "mime_type"), "audio/wav"),
                            data,
                            "predictions[%d].audioContent".formatted(index)
                    );
                }
            }
        }
        throw new IllegalStateException("Google Lyria 2 returned no audioContent for model " + model + ".");
    }

    private String buildPrompt(String prompt, String negativePrompt, String layerType, double durationSeconds) {
        String exclusions = firstText(negativePrompt, "vocals, lyrics, singing, spoken dialogue, narration, voiceover, copyrighted melodies, famous songs, artist imitation");
        return """
                Create a production-ready audio clip for a creator-video timeline.

                Request: %s
                Clip role: %s
                Target duration: %.1f seconds. If the model returns a longer standard clip, keep it clean and loopable so the editor can trim it.

                Requirements:
                - Instrumental or non-verbal sound only.
                - Keep dialogue space clear; do not generate speech.
                - Do not generate voice, vocals, lyrics, narration, or dialogue.
                - Make it royalty-safe and original.
                - Avoid: %s.
                """.formatted(
                stringValue(prompt, "Generate clean background music."),
                stringValue(layerType, "music"),
                Math.max(0.25, durationSeconds),
                exclusions
        ).trim();
    }

    private String normalizeLayerType(String layerType) {
        String normalized = stringValue(layerType, "music").toLowerCase().replace("-", "_").replace(" ", "_");
        if ("spoken".equals(normalized) || "voice".equals(normalized)) {
            return normalized;
        }
        if ("dialogue".equals(normalized)) {
            return "dialogue";
        }
        if ("ambient_bed".equals(normalized)) {
            return "ambience";
        }
        if ("background_music".equals(normalized) || "bgm".equals(normalized)) {
            return "music";
        }
        if (List.of("music", "ambience", "foley", "sfx", "sync_hit").contains(normalized)) {
            return normalized;
        }
        return "foley";
    }

    private byte[] decodeAudio(String value) {
        String data = stringValue(value, "");
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma >= 0) {
            data = data.substring(comma + 1);
        }
        return Base64.getDecoder().decode(data);
    }

    private String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return "";
            }
            current = item.matches("\\d+") ? current.path(Integer.parseInt(item)) : current.path(item);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private record InlineAudio(
            String mimeType,
            String base64Data,
            String responsePath
    ) {
    }

    public record GeneratedAudio(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse
    ) {
    }
}
