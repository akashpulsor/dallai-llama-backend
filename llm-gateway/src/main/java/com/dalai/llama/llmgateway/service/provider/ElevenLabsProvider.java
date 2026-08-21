package com.dalai.llama.llmgateway.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ElevenLabs -- a real, direct provider (not behind the fal.ai meta-provider), same principle as
 * GoogleGeminiProvider: one adapter, one credential set, calling ElevenLabs' actual documented
 * REST API rather than best-effort field-name guesses (unlike the fal.ai voice/lip-sync models,
 * ElevenLabs' contract is stable and well-known).
 *
 * <p>v1 slice is TTS only ({@code POST /v1/text-to-speech/{voice_id}}) -- voice cloning
 * ({@code POST /v1/voices/add}) is a real, separate multipart-upload contract (it wants an audio
 * file, not a URL, so a reference_audio_url would need to be downloaded and re-uploaded first)
 * and is a named fast-follow, not built this pass.
 *
 * <p><b>Content shape is different from every other provider here on purpose:</b> ElevenLabs'
 * TTS call returns raw audio bytes synchronously (audio/mpeg), not a hosted URL to poll/fetch --
 * there's no intermediate provider-hosted URL the way fal.ai's queue API or Seedance's output
 * has. Rather than give llm-gateway its own MinIO write path (a real boundary it has deliberately
 * not crossed so far -- see doc's "v1 does not persist response content" note), the bytes are
 * returned as a {@code data:audio/mpeg;base64,...} URI in {@link LlmResponse#content()}. The
 * calling service's own asset-persistence step (already built for both video-generation-service
 * and post-production-service) decodes and stores it exactly like any other result.
 */
@Slf4j
@Component
public class ElevenLabsProvider implements LlmProvider {

    private final WebClient webClient;
    private final String apiKey;

    public ElevenLabsProvider(
            @Value("${llm-gateway.elevenlabs.base-url}") String baseUrl,
            @Value("${llm-gateway.elevenlabs.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String providerId() {
        return "elevenlabs";
    }

    @Override
    public Mono<LlmResponse> generate(CanonicalRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("ELEVENLABS_API_KEY is not configured", false));
        }
        if (!"tts".equals(request.modelType())) {
            return Mono.error(new LlmProviderException(
                    "ElevenLabsProvider only supports type=tts, got " + request.modelType(), false));
        }
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        String voiceId = String.valueOf(params.getOrDefault("voice_id", ""));
        if (voiceId.isBlank()) {
            return Mono.error(new LlmProviderException("params.voice_id is required for ElevenLabs TTS", false));
        }
        String text = request.messages() == null ? "" : request.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .map(m -> m.content() == null ? "" : m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        // ElevenLabs' own multilingual model -- language is implicit in the text/voice, not a
        // separate request field on this endpoint.
        body.put("model_id", "eleven_multilingual_v2");

        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 30000;
        return webClient.post()
                .uri("/v1/text-to-speech/{voiceId}", voiceId)
                .header("xi-api-key", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(audioBytes -> {
                    if (audioBytes == null || audioBytes.length == 0) {
                        throw new LlmProviderException("ElevenLabs returned no audio", false);
                    }
                    String dataUri = "data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(audioBytes);
                    return new LlmResponse(dataUri, 0, 0, "COMPLETED", java.util.List.of());
                })
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "ElevenLabs call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("ElevenLabs call failed: " + ex.getMessage(), true, ex));
    }
}
