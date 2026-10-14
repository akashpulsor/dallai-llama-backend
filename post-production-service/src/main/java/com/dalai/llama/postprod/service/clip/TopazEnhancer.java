package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.service.PostProductionException;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Topaz on fal.ai, through llm-gateway: frame interpolation that GENERATES the frames a slowed clip
 * is missing, and upscaling of the joined film. Both take a public video URL and return one.
 *
 * <p>Each call runs for minutes, so it has its own timeout rather than the client's default.
 */
@Component
public class TopazEnhancer {

    private final LlmGatewayClient gateway;
    private final String interpolateModel;
    private final String interpolationMode;
    private final String upscaleModel;
    private final String upscaleMode;
    private final Duration timeout;

    public TopazEnhancer(LlmGatewayClient gateway,
                         @Value("${post-production.topaz.interpolate-model:topaz/interpolate/video}") String interpolateModel,
                         @Value("${post-production.topaz.interpolation-mode:Chronos}") String interpolationMode,
                         @Value("${post-production.topaz.upscale-model:fal-ai/topaz/upscale/video}") String upscaleModel,
                         @Value("${post-production.topaz.upscale-mode:Proteus}") String upscaleMode,
                         @Value("${post-production.topaz.timeout-ms:1300000}") long timeoutMs) {
        this.gateway = gateway;
        this.interpolateModel = interpolateModel;
        this.interpolationMode = interpolationMode;
        this.upscaleModel = upscaleModel;
        this.upscaleMode = upscaleMode;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** The clip slowed {@code slowdownFactor} times, every in-between frame generated, at {@code fps}. */
    public String interpolate(UUID tenantId, UUID projectId, String sourceUrl, int slowdownFactor, int fps,
                              double outputSeconds, String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceUrl);
        params.put("model", interpolationMode);
        params.put("slowdown_factor", slowdownFactor);
        params.put("target_fps", fps);
        params.put("H264_output", true);
        params.put("duration_seconds", outputSeconds);
        return call(tenantId, projectId, interpolateModel, params, idempotencyKey, "Slow this video down.");
    }

    /** The video {@code factor} times larger in each dimension. */
    public String upscale(UUID tenantId, UUID projectId, String sourceUrl, double factor, double durationSeconds,
                          String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceUrl);
        params.put("model", upscaleMode);
        params.put("upscale_factor", factor);
        params.put("H264_output", true);
        params.put("duration_seconds", durationSeconds);
        return call(tenantId, projectId, upscaleModel, params, idempotencyKey, "Upscale this video.");
    }

    private String call(UUID tenantId, UUID projectId, String model, Map<String, Object> params, String key, String ask) {
        LlmGatewayChatResponse response = gateway.chat(tenantId.toString(), key,
                new LlmGatewayChatRequest(model, List.of(new LlmGatewayMessage("user", ask)), params, null, null)
                        .withProjectId(projectId), timeout);
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("Topaz returned no video (" + model + ")");
        }
        return response.response();
    }
}
