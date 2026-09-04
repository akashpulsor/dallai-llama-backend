package com.dalai.llama.llmgateway.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ElevenLabs -- a real, direct provider (not behind the fal.ai meta-provider), same principle as
 * GoogleGeminiProvider: one adapter, one credential set, calling ElevenLabs' actual documented
 * REST API rather than best-effort field-name guesses (unlike the fal.ai voice/lip-sync models,
 * ElevenLabs' contract is stable and well-known).
 *
 * <p>Two operations: TTS ({@code POST /v1/text-to-speech/{voice_id}}, an already-registered
 * voice speaking new text) and voice cloning ({@code POST /v1/voices/add}, {@code type=
 * "voice_clone"} -- a real, separate multipart-upload contract: ElevenLabs wants an audio file,
 * not a URL, so {@code params.reference_audio_url} is downloaded here and re-uploaded as
 * multipart). Cloning returns a {@code voice_id} (no {@code text} param, mirroring
 * {@code FalAiProvider}'s voice_clone convention) which the caller then feeds straight into a
 * {@code type="tts"} call on this same provider to actually synthesize speech in that voice --
 * cloning alone never produces audio, ElevenLabs has no clone+synthesize fused endpoint the way
 * fal.ai's MiniMax model does.
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

    /** Business-decided revenue line, not a discovered ElevenLabs cost -- see cloneVoice's own
     * comment. Priced at the same real per-character rate elevenlabs-tts-v1's rate_card row uses. */
    private static final int FLAT_CLONE_CHARGE_CHAR_EQUIVALENT = 1000;

    private final WebClient webClient;
    private final String apiKey;

    public ElevenLabsProvider(
            @Value("${llm-gateway.elevenlabs.base-url}") String baseUrl,
            @Value("${llm-gateway.elevenlabs.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        // Same 256KB-default-buffer problem as GoogleGeminiProvider -- raw audio/mpeg bytes,
        // base64-inlined into LlmResponse.content, routinely exceed that for anything beyond a
        // couple seconds of speech. 16MB matches creator-service's proven GoogleGenAiClientFactory
        // value.
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
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
        if ("voice_clone".equals(request.modelType())) {
            return cloneVoice(request);
        }
        if ("music".equals(request.modelType())) {
            return composeMusic(request);
        }
        if (!"tts".equals(request.modelType())) {
            return Mono.error(new LlmProviderException(
                    "ElevenLabsProvider only supports type=tts, type=voice_clone, or type=music, got " + request.modelType(), false));
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
                    // Character count as "input tokens" so LlmGatewayService.computeCost's existing
                    // generic input-token-cost path bills this correctly with zero new billing logic
                    // -- ElevenLabs' own real rate is $/1000 characters (eleven_multilingual_v2 =
                    // $0.10/1000 chars under their May-2026 pay-as-you-go pricing, 1 char = 1 credit),
                    // which is exactly what a per-input-token rate_card row already expresses.
                    return new LlmResponse(dataUri, text.length(), 0, "COMPLETED", java.util.List.of());
                })
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "ElevenLabs call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("ElevenLabs call failed: " + ex.getMessage(), true, ex));
    }

    /** {@code params.reference_audio_url} (same param name {@code FalAiProvider.voiceCloneRequestBody}
     * uses) is fetched, then re-uploaded as multipart to {@code POST /v1/voices/add} -- ElevenLabs'
     * actual documented contract takes a file, not a URL. {@code params.name} is optional; without
     * it every clone would collide on ElevenLabs' own default name, so a unique fallback is
     * generated. Result content is the bare {@code voice_id} string, same shape a caller gets back
     * from fal.ai's voice_clone (no synthesized audio -- see class javadoc). */
    private Mono<LlmResponse> cloneVoice(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        String referenceAudioUrl = String.valueOf(params.getOrDefault("reference_audio_url", ""));
        if (referenceAudioUrl.isBlank() || "null".equals(referenceAudioUrl)) {
            return Mono.error(new LlmProviderException("params.reference_audio_url is required for ElevenLabs voice cloning", false));
        }
        String name = String.valueOf(params.getOrDefault("name", "")).isBlank()
                ? "clone-" + UUID.randomUUID()
                : String.valueOf(params.get("name"));
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 60000;

        return WebClient.create().get()
                .uri(referenceAudioUrl)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMillis(timeoutMs))
                .flatMap(audioBytes -> {
                    if (audioBytes == null || audioBytes.length == 0) {
                        return Mono.error(new LlmProviderException("reference_audio_url returned no audio to clone from", false));
                    }
                    MultipartBodyBuilder multipart = new MultipartBodyBuilder();
                    multipart.part("name", name);
                    multipart.part("files", new ByteArrayResource(audioBytes) {
                        @Override
                        public String getFilename() {
                            return "reference.mp3";
                        }
                    });
                    return webClient.post()
                            .uri("/v1/voices/add")
                            .header("xi-api-key", apiKey)
                            .body(BodyInserters.fromMultipartData(multipart.build()))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .timeout(Duration.ofMillis(timeoutMs));
                })
                .map(response -> {
                    Object voiceId = response == null ? null : response.get("voice_id");
                    if (voiceId == null || String.valueOf(voiceId).isBlank()) {
                        throw new LlmProviderException("ElevenLabs voice clone response had no voice_id: " + response, false);
                    }
                    // Cloning itself carries no ElevenLabs credit cost (not draw from the character
                    // pool the way TTS/music are) -- deliberately charged anyway as a flat,
                    // business-decided revenue line rather than passed through at raw cost: billed
                    // as a fixed 1000-character-equivalent at TTS's own real per-character rate
                    // (rate_card.input_token_cost, same row shape as elevenlabs-tts-v1), so the
                    // number stays tied to a real published rate rather than an invented one.
                    return new LlmResponse(String.valueOf(voiceId), FLAT_CLONE_CHARGE_CHAR_EQUIVALENT, 0, "COMPLETED", java.util.List.of());
                })
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "ElevenLabs voice clone call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("ElevenLabs voice clone call failed: " + ex.getMessage(), true, ex));
    }

    /** Verified against ElevenLabs' real, documented {@code POST /v1/music} -- {@code prompt}
     * (the only field this codebase's callers ever set; {@code composition_plan} is a separate,
     * mutually-exclusive way to shape the request this pass doesn't build) and
     * {@code music_length_ms} (3000-600000, only valid alongside {@code prompt}) in; raw audio
     * bytes out, synchronously -- same non-hosted-URL shape as TTS, so it's wrapped in the same
     * {@code data:audio/mpeg;base64,...} convention. */
    private Mono<LlmResponse> composeMusic(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        String prompt = String.valueOf(params.getOrDefault("prompt", ""));
        if (prompt.isBlank() || "null".equals(prompt)) {
            return Mono.error(new LlmProviderException("params.prompt is required for ElevenLabs music generation", false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        Object lengthMs = params.get("music_length_ms");
        if (lengthMs != null) {
            body.put("music_length_ms", lengthMs);
        }
        Object forceInstrumental = params.get("force_instrumental");
        if (forceInstrumental != null) {
            body.put("force_instrumental", forceInstrumental);
        }

        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 60000;
        return webClient.post()
                .uri("/v1/music")
                .header("xi-api-key", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(audioBytes -> {
                    if (audioBytes == null || audioBytes.length == 0) {
                        throw new LlmProviderException("ElevenLabs returned no music audio", false);
                    }
                    String dataUri = "data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(audioBytes);
                    return new LlmResponse(dataUri, 0, 0, "COMPLETED", java.util.List.of());
                })
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "ElevenLabs music call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("ElevenLabs music call failed: " + ex.getMessage(), true, ex));
    }
}
