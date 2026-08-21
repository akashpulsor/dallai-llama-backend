package com.dalai.llama.llmgateway.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * fal.ai adapter -- a meta-provider fronting many underlying models (Seedance, MuseTalk, Kling,
 * etc., doc §7) behind one account/API key. v1 wires up Seedance's video generation queue API for
 * real; other fal.ai-hosted models register their own {@code model_master} row later and reuse
 * this same adapter (that's the whole point of the meta-provider design -- one adapter, many
 * model_master rows, doc §7).
 *
 * <p>This is a relocation, not new logic: the submit -> poll {@code status_url} -> fetch
 * {@code response_url} sequence mirrors {@code ScreenplayVideoProviderGenerationService}'s
 * already-proven fal.ai/Seedance integration in creator-service, rebuilt as a non-blocking
 * {@link Mono} chain (via {@code Mono.delay} between polls) so it plugs into
 * {@code LlmGatewayService}'s existing cancellation/timeout machinery instead of blocking a
 * thread for the poll loop's duration.
 */
@Slf4j
@Component
public class FalAiProvider implements LlmProvider {

    private static final long POLL_INTERVAL_MS = 3000L;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final String apiKey;

    /** Backs the generic {@link #cancelRequest(String)} contract -- fal.ai's own request_id ->
     * its cancel_url, for every call currently in flight on this pod. Populated once the submit
     * response returns, removed on any terminal signal (success, error, or cancellation) via
     * doFinally so a stale entry never lingers past the call it belongs to. */
    private final Map<String, String> cancelUrlsByRequestId = new ConcurrentHashMap<>();

    public FalAiProvider(
            @Value("${llm-gateway.fal.base-url}") String baseUrl,
            @Value("${llm-gateway.fal.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String providerId() {
        return "fal.ai";
    }

    @Override
    public Mono<LlmResponse> generate(CanonicalRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("FAL_API_KEY is not configured", false));
        }
        Map<String, Object> body = toFalRequestBody(request);
        String endpoint = falEndpoint(request);
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 180000;

        return webClient.post()
                .uri("/" + endpoint)
                .header("Authorization", "Key " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .flatMap(submitResponse -> {
                    String statusUrl = firstText(submitResponse, "status_url");
                    String responseUrl = firstText(submitResponse, "response_url");
                    // fal.ai's queue API includes cancel_url alongside status_url/response_url for
                    // queued requests; fall back to the conventional {status_url}/cancel shape on
                    // the rare app that omits it, so a best-effort cancel is still attempted.
                    String rawCancelUrl = firstText(submitResponse, "cancel_url");
                    if (rawCancelUrl.isBlank() && !statusUrl.isBlank()) {
                        rawCancelUrl = statusUrl + "/cancel";
                    }
                    if (statusUrl.isBlank() || responseUrl.isBlank()) {
                        return Mono.error(new LlmProviderException(
                                "fal.ai submit response missing status_url/response_url: " + submitResponse, false));
                    }
                    String cancelUrl = rawCancelUrl;
                    String requestId = firstText(submitResponse, "request_id");
                    if (!requestId.isBlank()) {
                        cancelUrlsByRequestId.put(requestId, cancelUrl);
                    }
                    return pollUntilComplete(statusUrl, responseUrl, cancelUrl, 0, timeoutMs)
                            // Fires when the Reactor subscription is cancelled -- i.e. downstream
                            // called future.cancel(true), either a user-initiated
                            // POST /v1/jobs/{id}/cancel or LlmGatewayService's own gateway-level
                            // timeout margin. Without this, our poll loop simply stops watching
                            // while fal.ai keeps generating (and billing their side) regardless.
                            .doOnCancel(() -> cancelUpstream(cancelUrl))
                            .doFinally(signal -> {
                                if (!requestId.isBlank()) {
                                    cancelUrlsByRequestId.remove(requestId);
                                }
                            });
                })
                .map(result -> toLlmResponse(result, safeType(request)))
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "fal.ai call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("fal.ai call failed: " + ex.getMessage(), true, ex));
    }

    private Mono<Map<String, Object>> pollUntilComplete(String statusUrl, String responseUrl, String cancelUrl, long elapsedMs, int timeoutMs) {
        if (elapsedMs > timeoutMs) {
            // Our own patience ran out before fal.ai's queue did -- this is an error completion,
            // not a Reactor cancellation, so doOnCancel above never fires for this path. Must
            // cancel upstream here explicitly, or fal.ai keeps generating (and billing) a result
            // nobody is waiting for anymore.
            cancelUpstream(cancelUrl);
            return Mono.error(new LlmProviderException(
                    "fal.ai video generation timed out after " + timeoutMs + "ms", true));
        }
        return webClient.get()
                .uri(statusUrl)
                .header("Authorization", "Key " + apiKey)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .flatMap(status -> {
                    String state = String.valueOf(status.getOrDefault("status", "")).toUpperCase(java.util.Locale.ROOT);
                    if ("COMPLETED".equals(state)) {
                        return webClient.get()
                                .uri(responseUrl)
                                .header("Authorization", "Key " + apiKey)
                                .retrieve()
                                .bodyToMono(MAP_TYPE);
                    }
                    if ("ERROR".equals(state) || "FAILED".equals(state)) {
                        return Mono.error(new LlmProviderException("fal.ai video generation failed: " + status, false));
                    }
                    return Mono.delay(Duration.ofMillis(POLL_INTERVAL_MS))
                            .then(pollUntilComplete(statusUrl, responseUrl, cancelUrl, elapsedMs + POLL_INTERVAL_MS, timeoutMs));
                });
    }

    /** The generic {@link LlmProvider} contract, backed by {@link #cancelUrlsByRequestId}. Lets a
     * caller that somehow learns fal.ai's request_id independently of the in-flight Mono (there is
     * no such caller yet -- doOnCancel/the timeout branch already cover the two real paths) cancel
     * it explicitly too, without needing fal.ai-specific knowledge of cancel_url. */
    @Override
    public void cancelRequest(String providerRequestId) {
        String cancelUrl = cancelUrlsByRequestId.get(providerRequestId);
        if (cancelUrl == null) {
            log.warn("cancelRequest called for unknown/already-finished providerRequestId={}", providerRequestId);
            return;
        }
        cancelUpstream(cancelUrl);
    }

    /** Best-effort, fire-and-forget -- a failed cancel call must never block or fail the caller's
     * own already-in-progress error/cancellation handling. Worst case fal.ai finishes a generation
     * nobody retrieves; this reduces how often that happens, it can't guarantee zero. */
    private void cancelUpstream(String cancelUrl) {
        if (cancelUrl == null || cancelUrl.isBlank()) {
            return;
        }
        webClient.put()
                .uri(cancelUrl)
                .header("Authorization", "Key " + apiKey)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .subscribe(
                        ignored -> log.info("Cancelled upstream fal.ai request cancelUrl={}", cancelUrl),
                        ex -> log.warn("Failed to cancel upstream fal.ai request cancelUrl={} errorMessage={}", cancelUrl, ex.getMessage())
                );
    }

    Map<String, Object> toFalRequestBody(CanonicalRequest request) {
        return switch (safeType(request)) {
            case "voice_clone" -> voiceCloneRequestBody(request);
            case "lip_sync" -> lipSyncRequestBody(request);
            case "tts" -> ttsRequestBody(request);
            case "foley", "music" -> audioGenerationRequestBody(request);
            case "transcription" -> transcriptionRequestBody(request);
            case "video_edit" -> videoEditRequestBody(request);
            default -> videoRequestBody(request);
        };
    }

    private Map<String, Object> transcriptionRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("audio_url", params.get("source_video_url"));
        return body;
    }

    /** Video-to-video editing (Kling-style, doc's "editing scenes through fal" note): a portion
     * of a source video, a freeform edit instruction, and an optional reference image. */
    private Map<String, Object> videoEditRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("video_url", params.get("source_video_url"));
        body.putIfAbsent("prompt", promptFromMessages(request));
        return body;
    }

    private Map<String, Object> videoRequestBody(CanonicalRequest request) {
        String prompt = promptFromMessages(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        if (params.get("negative_prompt") != null) {
            body.put("negative_prompt", params.get("negative_prompt"));
        }
        if (params.get("duration_seconds") != null) {
            body.put("duration", params.get("duration_seconds"));
        }
        if (params.get("aspect_ratio") != null) {
            body.put("aspect_ratio", params.get("aspect_ratio"));
        }
        Object referenceImageUrls = params.get("reference_image_urls");
        if (referenceImageUrls instanceof List<?> urls && !urls.isEmpty()) {
            body.put(urls.size() == 1 ? "image_url" : "image_urls", urls.size() == 1 ? urls.get(0) : urls);
        }
        return body;
    }

    /** Field names are best-effort, not yet verified against a specific fal.ai model's real
     * schema -- every candidate model (voice-clone, lip-sync, TTS) has its own app-specific input
     * field names on fal.ai, and none has been confirmed live yet (see doc note on this gap).
     * Passing params straight through as the body, plus these commonly-used aliases, means wiring
     * a specific confirmed model later is a matter of confirming/renaming keys here, not
     * restructuring the call path. */
    private Map<String, Object> voiceCloneRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("audio_url", params.get("reference_audio_url"));
        return body;
    }

    private Map<String, Object> lipSyncRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("video_url", params.get("source_video_url"));
        body.putIfAbsent("audio_url", params.get("dialogue_audio_url"));
        return body;
    }

    private Map<String, Object> ttsRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("text", promptFromMessages(request));
        return body;
    }

    /** Foley (sound-effects-from-video) and music generation both reduce to "a text prompt
     * describing the desired audio, optionally against a reference video, for N seconds" on
     * fal.ai's common audio-gen app shape -- same params map pass-through as the others, with
     * "prompt" defaulted from the message text so a caller can pass either a params.prompt or a
     * plain chat message. */
    private Map<String, Object> audioGenerationRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("prompt", promptFromMessages(request));
        return body;
    }

    private String promptFromMessages(CanonicalRequest request) {
        return request.messages() == null ? "" : request.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .map(m -> m.content() == null ? "" : m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private String falEndpoint(CanonicalRequest request) {
        // Video models are called at a /text-to-video or /image-to-video sub-path (Seedance's own
        // fal.ai convention); other model types are called directly at their own app slug -- fal
        // apps outside the video family don't share that sub-path convention.
        if (!"video".equals(safeType(request))) {
            return request.modelId();
        }
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Object referenceImageUrls = params.get("reference_image_urls");
        boolean imageToVideo = referenceImageUrls instanceof List<?> urls && !urls.isEmpty();
        return request.modelId() + (imageToVideo ? "/image-to-video" : "/text-to-video");
    }

    private String safeType(CanonicalRequest request) {
        return request.modelType() == null ? "video" : request.modelType();
    }

    /** Result shapes are best-effort per type, same caveat as the request-body builders above --
     * not yet verified against a specific confirmed fal.ai model response. video/lip_sync/
     * video_edit assume a {@code {video: {url}}} envelope (fal's common convention for anything
     * producing a clip); tts/foley/music assume {@code {audio: {url}}}; voice_clone assumes a
     * bare {@code voice_id} string, since cloning returns an identifier to reuse, not a playable
     * asset; transcription assumes a bare {@code text} string, the transcript itself. */
    @SuppressWarnings("unchecked")
    private LlmResponse toLlmResponse(Map<String, Object> result, String modelType) {
        String error = firstText(result, "error");
        if (!error.isBlank()) {
            throw new LlmProviderException("fal.ai result contained an error: " + error, false);
        }
        String content = switch (modelType) {
            case "voice_clone" -> firstNonBlank(firstText(result, "voice_id"), firstText(result, "voiceId"));
            case "tts", "foley", "music" -> nestedUrl(result, "audio");
            case "transcription" -> firstText(result, "text");
            default -> nestedUrl(result, "video"); // video, lip_sync, video_edit
        };
        if (content.isBlank()) {
            throw new LlmProviderException("fal.ai result did not contain the expected output for type=" + modelType + ": " + result, false);
        }
        // No meaningful input/output "token" count for any of these types -- cost is computed
        // from duration/characters via the rate card's own convention, not token counts; v1
        // reports 0/0 here and leaves that billing model as a documented follow-up.
        return new LlmResponse(content, 0, 0, "COMPLETED", List.of());
    }

    @SuppressWarnings("unchecked")
    private String nestedUrl(Map<String, Object> result, String key) {
        Object nested = result.get(key);
        if (nested instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url == null ? "" : String.valueOf(url);
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            Object message = nested.get("message");
            return message == null ? "" : String.valueOf(message);
        }
        return value == null ? "" : String.valueOf(value);
    }
}
