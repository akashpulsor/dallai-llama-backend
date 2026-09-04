package com.dalai.llama.llmgateway.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
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
        // Same 256KB-default-buffer problem as GoogleGeminiProvider -- fal.ai's response_url fetch
        // can return a base64-inlined image/video/voice-clone payload well past that, so raise it
        // to 16MB (matches creator-service's proven GoogleGenAiClientFactory value) rather than
        // silently truncating.
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
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
                .map(result -> toLlmResponse(result, safeType(request), request.params()))
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
            case "audio_video_merge" -> audioVideoMergeRequestBody(request);
            case "tts" -> ttsRequestBody(request);
            case "foley", "music" -> audioGenerationRequestBody(request);
            case "transcription" -> transcriptionRequestBody(request);
            case "video_edit" -> videoEditRequestBody(request);
            case "image" -> imageRequestBody(request);
            case "upscale" -> upscaleRequestBody(request);
            default -> videoRequestBody(request);
        };
    }

    /** {@code fal-ai/ffmpeg-api/merge-audio-video}: lays one audio track onto one video at an
     * optional offset -- the "auto-dub" mux step for video-generation-service's beat-matched
     * cloned-voice dialogue (see doc's dialogue-beats design). Deliberately not the same method as
     * {@link #lipSyncRequestBody} even though both are a video_url + audio_url passthrough today --
     * this one additionally forwards {@code start_offset}, and the two fal.ai apps are unrelated
     * (this one never warps the video to match the audio, it only places a track). */
    private Map<String, Object> audioVideoMergeRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("video_url", params.get("source_video_url"));
        body.putIfAbsent("audio_url", params.get("dialogue_audio_url"));
        return body;
    }

    /** Identity-preserving image generation (FLUX_PULID and similar face-conditioned apps): one
     * or two reference image URLs plus a text prompt. Same {@code reference_image_urls} param
     * name {@link #videoRequestBody} already uses, so a caller conditioning a shot's identity on
     * a cast reference photo passes the same param shape regardless of image vs. video output. */
    private Map<String, Object> imageRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("prompt", promptFromMessages(request));
        Object referenceImageUrls = params.get("reference_image_urls");
        if (referenceImageUrls instanceof List<?> urls && !urls.isEmpty()) {
            body.putIfAbsent(urls.size() == 1 ? "image_url" : "image_urls", urls.size() == 1 ? urls.get(0) : urls);
        }
        return body;
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

    /** Video-to-video-to-{larger video}: {@code fal-ai/topaz/upscale/video}, verified against
     * fal.ai's real documented schema -- {@code video_url} (required) plus optional tuning knobs
     * (upscale_factor, target_fps, compression/noise/halo/grain/recover_detail, model,
     * H264_output) that callers may pass straight through via params; only video_url needs
     * renaming from this codebase's established {@code source_video_url} convention (same
     * convention {@link #lipSyncRequestBody}/{@link #videoEditRequestBody} already use). Response
     * envelope is fal's common {@code {video: {url}}} shape, already covered by {@link
     * #toLlmResponse}'s default case -- no response-side change needed. */
    private Map<String, Object> upscaleRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("video_url", params.get("source_video_url"));
        body.remove("source_video_url");
        // Present in params purely for LlmGatewayService.computeCost's duration-priced billing
        // (see LlmGatewayUpscaleGenerationService) -- not part of Topaz's real documented schema,
        // so it's stripped here rather than sent as an unrecognized field on the actual API call.
        body.remove("duration_seconds");
        return body;
    }

    private Map<String, Object> videoRequestBody(CanonicalRequest request) {
        if (isWanModel(request.modelId())) {
            return wanVideoRequestBody(request);
        }
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
        // Verified against fal.ai's real Seedance schema: resolution is 480p/720p, default 720p
        // (no 1080p option on this Fast variant, unlike Wan). Sent explicitly rather than left to
        // fal.ai's own default -- same "never depend on an upstream default silently matching our
        // rate_card row" discipline as DEFAULT_WAN_RESOLUTION below, even though today's real
        // default happens to already be the cheaper option.
        Object resolution = params.get("resolution");
        body.put("resolution", resolution != null ? resolution : "720p");
        // Verified against fal.ai's real Seedance schema: generate_audio (boolean, default true)
        // covers native sound effects/ambient/lip-synced speech. video-generation-service sets
        // this false when it's about to lay its own beat-matched cloned-voice audio under the
        // silent result instead of trusting Seedance's own synthesis.
        if (params.get("generate_audio") != null) {
            body.put("generate_audio", params.get("generate_audio"));
        }
        Object referenceImageUrls = params.get("reference_image_urls");
        if (referenceImageUrls instanceof List<?> urls && !urls.isEmpty()) {
            body.put(urls.size() == 1 ? "image_url" : "image_urls", urls.size() == 1 ? urls.get(0) : urls);
        }
        // Verified against fal.ai's Seedance schema: seed (integer). Sending it makes generation
        // deterministic -- the primary continuity mechanism across shots. Never send Long above
        // 2^31-1 raw; Seedance's field is a 32-bit signed int, clamp via modulo.
        Object seed = params.get("seed");
        if (seed instanceof Number seedNumber) {
            body.put("seed", (int) (seedNumber.longValue() & 0x7FFFFFFFL));
        }
        return body;
    }

    /** {@code alibaba/wan-3.0-prime/image-to-video} does not share Seedance's field names despite
     * both being type=video: verified against fal.ai's real documented schema, it wants {@code
     * start_image_url} (singular, required for image-to-video) instead of {@code
     * image_url}/{@code image_urls}, and {@code audio} (boolean, default true) instead of {@code
     * generate_audio} for whether the generated clip carries native sound. duration/aspect_ratio
     * map the same as Seedance's already-generic handling. Dispatched by model id rather than a
     * dedicated {@code model_master.type} value so Wan still bills/lists identically to every
     * other type=video model (see {@code LlmGatewayService.computeCost}'s {@code
     * "video".equals(modelType)} per-second-billing branch, and {@code GET /v1/models?type=video}
     * -- a caller listing video models shouldn't have to know Wan is somehow different). */
    /** Cost-policy default: 480p ($0.05/s) unless a caller explicitly asks for a higher
     * resolution -- fal.ai's own default is 1080p ($0.20/s), which must never be reached by
     * omission. Generate cheap, upscale for delivery quality (see FalAiProvider.upscaleRequestBody
     * and the fal-ai/topaz/upscale/video rate_card row) rather than paying 1080p generation cost. */
    private static final String DEFAULT_WAN_RESOLUTION = "480p";

    private Map<String, Object> wanVideoRequestBody(CanonicalRequest request) {
        String prompt = promptFromMessages(request);
        Map<String, Object> body = new LinkedHashMap<>();
        if (!prompt.isBlank()) {
            body.put("prompt", prompt);
        }
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        if (params.get("duration_seconds") != null) {
            body.put("duration", params.get("duration_seconds"));
        }
        Object resolution = params.get("resolution");
        body.put("resolution", resolution != null ? resolution : DEFAULT_WAN_RESOLUTION);
        if (params.get("aspect_ratio") != null) {
            body.put("aspect_ratio", params.get("aspect_ratio"));
        }
        if (params.get("generate_audio") != null) {
            body.put("audio", params.get("generate_audio"));
        }
        Object referenceImageUrls = params.get("reference_image_urls");
        if (referenceImageUrls instanceof List<?> urls && !urls.isEmpty()) {
            body.put("start_image_url", urls.get(0));
        }
        // Same seed passthrough as seedanceRequestBody; see that method for the continuity rationale.
        // fal.ai's Wan schema also documents seed as a 32-bit signed int.
        Object seed = params.get("seed");
        if (seed instanceof Number seedNumber) {
            body.put("seed", (int) (seedNumber.longValue() & 0x7FFFFFFFL));
        }
        return body;
    }

    /** {@code alibaba/wan-*} app slugs -- the one family of type=video models on fal.ai that
     * doesn't share Seedance's field names, see {@link #wanVideoRequestBody}. */
    private boolean isWanModel(String modelId) {
        return modelId != null && modelId.startsWith("alibaba/wan");
    }

    /** Verified against fal.ai's real, documented fal-ai/minimax/voice-clone schema: {@code
     * audio_url} (required, the reference sample) plus an optional {@code text} that makes the
     * SAME call also synthesize a preview from the freshly cloned voice -- MiniMax's endpoint
     * fuses cloning and synthesis into one call rather than "clone once, reuse a voice_id
     * elsewhere" the way ElevenLabs' two-endpoint model works. Deliberately does NOT default
     * "text" from the chat messages the way {@link #ttsRequestBody} does -- whether {@code text}
     * is explicitly present in params is exactly the signal {@link #toLlmResponse} uses to know
     * whether to read back {@code custom_voice_id} (a bare clone, no text given) or {@code
     * audio.url} (clone+synthesize, text given). */
    private Map<String, Object> voiceCloneRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("audio_url", params.get("reference_audio_url"));
        body.remove("reference_audio_url");
        return body;
    }

    private Map<String, Object> lipSyncRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("video_url", params.get("source_video_url"));
        body.putIfAbsent("audio_url", params.get("dialogue_audio_url"));
        // Present in params purely for LlmGatewayService.computeCost's duration-priced billing
        // (see LlmGatewayLipSyncGenerationService) -- not part of sync-lipsync's real schema.
        body.remove("duration_seconds");
        return body;
    }

    /** Verified against fal.ai's real fal-ai/elevenlabs/tts/multilingual-v2 schema: {@code text}
     * plus {@code voice} (a voice NAME, e.g. "Rachel" -- not the {@code voice_id} field name/shape
     * every other provider in this codebase uses). Callers here still pass {@code voice_id} (the
     * established param name across VoiceSynthesisService et al.), so it's renamed to fal.ai's
     * real {@code voice} field rather than asking every caller to know fal.ai's specific spelling. */
    private Map<String, Object> ttsRequestBody(CanonicalRequest request) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.putIfAbsent("text", promptFromMessages(request));
        Object voiceId = params.get("voice_id");
        if (voiceId != null) {
            body.putIfAbsent("voice", voiceId);
            body.remove("voice_id");
        }
        body.remove("language");
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

    /** Result shapes: video/lip_sync/video_edit assume a {@code {video: {url}}} envelope (fal's
     * common convention for anything producing a clip); tts/foley/music assume {@code {audio:
     * {url}}}; transcription assumes a bare {@code text} string, the transcript itself -- these
     * are still best-effort/unverified for the types this pass didn't touch. voice_clone is
     * verified against fal-ai/minimax/voice-clone's real fused response ({@code custom_voice_id}
     * always present, {@code audio.url} present only when the request carried an explicit {@code
     * text} to synthesize) -- {@code requestParams} carries that same signal forward from the
     * request that produced this result, so a bare clone call reads back the voice id and a
     * clone+synthesize call reads back the audio URL, from the one fused endpoint. */
    @SuppressWarnings("unchecked")
    private LlmResponse toLlmResponse(Map<String, Object> result, String modelType, Map<String, Object> requestParams) {
        String error = firstText(result, "error");
        if (!error.isBlank()) {
            throw new LlmProviderException("fal.ai result contained an error: " + error, false);
        }
        boolean voiceCloneWantsAudio = requestParams != null && requestParams.get("text") != null
                && !String.valueOf(requestParams.get("text")).isBlank();
        String content = switch (modelType) {
            case "voice_clone" -> voiceCloneWantsAudio
                    ? nestedUrl(result, "audio")
                    : firstNonBlank(firstText(result, "custom_voice_id"), firstText(result, "voice_id"), firstText(result, "voiceId"));
            case "tts", "foley", "music" -> nestedUrl(result, "audio");
            case "transcription" -> firstText(result, "text");
            case "image" -> firstImageUrl(result);
            default -> nestedUrl(result, "video"); // video, lip_sync, video_edit
        };
        if (content.isBlank()) {
            throw new LlmProviderException("fal.ai result did not contain the expected output for type=" + modelType + ": " + result, false);
        }
        // beatoven/sound-effect-generation (type=foley) is a real, verified flat $0.01/request --
        // not duration- or token-priced, so a flat "1 unit" input-token count lets
        // LlmGatewayService.computeCost's existing generic input-token-cost path bill it correctly
        // (rate_card.input_token_cost = 0.01) with no new billing branch.
        //
        // fal-ai/minimax/voice-clone (type=voice_clone) is real, verified two-part pricing: $1.50
        // flat per clone request (input_token_cost, with inputTokens fixed at 1 -- a unit marker,
        // not a real token count) PLUS $0.30/1000 characters (output_token_cost = 0.0003/char)
        // ONLY when the fused call also synthesizes a preview (voiceCloneWantsAudio, same signal
        // `content` above already keys off). This maps the real two-component fal.ai price exactly
        // onto computeCost's existing input+output formula, no new billing branch needed.
        int inputTokens = 0;
        int outputTokens = 0;
        if ("foley".equals(modelType)) {
            inputTokens = 1;
        } else if ("voice_clone".equals(modelType)) {
            inputTokens = 1;
            outputTokens = voiceCloneWantsAudio ? String.valueOf(requestParams.get("text")).length() : 0;
        }
        // Every other type here still has no meaningful input/output "token" count -- 0/0 remains
        // a documented follow-up for those, not a claim they're free.
        return new LlmResponse(content, inputTokens, outputTokens, "COMPLETED", List.of());
    }

    /** fal.ai's common image-app envelope is {@code {images: [{url}, ...]}}; some apps return a
     * single {@code {image: {url}}} instead -- try the array first, fall back to the singular. */
    @SuppressWarnings("unchecked")
    private String firstImageUrl(Map<String, Object> result) {
        Object images = result.get("images");
        if (images instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object url = first.get("url");
            if (url != null) {
                return String.valueOf(url);
            }
        }
        return nestedUrl(result, "image");
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
