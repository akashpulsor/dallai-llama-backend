package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.web.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmGatewayVideoGenDispatchService implements VideoGenDispatchService {

    private final LlmGatewayClient llmGatewayClient;

    public LlmGatewayVideoGenDispatchService(LlmGatewayClient llmGatewayClient) {
        this.llmGatewayClient = llmGatewayClient;
    }

    @Override
    public DispatchResult dispatch(VideoGenJob job, String positivePrompt, String negativePrompt, VideoDispatchParams params) {
        String tenantId = job.getTenantId().toString();
        Map<String, Object> videoParams = new LinkedHashMap<>();
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            videoParams.put("negative_prompt", negativePrompt);
        }
        if (params != null && params.durationSeconds() != null) {
            videoParams.put("duration_seconds", params.durationSeconds());
        }
        if (params != null && params.aspectRatio() != null) {
            videoParams.put("aspect_ratio", params.aspectRatio());
        }
        if (params != null && params.generateAudio() != null) {
            videoParams.put("generate_audio", params.generateAudio());
        }
        if (params != null && params.referenceImageUrls() != null && !params.referenceImageUrls().isEmpty()) {
            videoParams.put("reference_image_urls", params.referenceImageUrls());
        }
        // Deterministic seed forwarded to fal.ai / whichever underlying provider. Same seed for
        // every shot in the same project (derived from locked_idea_id in ShotGenerationOrchestrator
        // .resolveSeed) is the primary continuity lever: character faces, set details, lighting
        // stay coherent shot-to-shot. Not sending it means fal.ai rolls fresh randomness each
        // call, which is exactly why continuity drifted across shots before.
        if (params != null && params.seed() != null) {
            videoParams.put("seed", params.seed());
        }

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId,
                "video-gen-job-" + job.getJobId(),
                new LlmGatewayChatRequest(
                        job.getModelId(),
                        List.of(new LlmGatewayMessage("user", positivePrompt)),
                        videoParams,
                        null,
                        null,
                        job.getProjectId()
                )
        );
        if (response == null) {
            throw VideoGenException.upstream("llm-gateway returned no response for job_id=" + job.getJobId());
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        String currency = "USD";
        return new DispatchResult(response.jobId(), response.response(), cost, currency);
    }

    @Override
    public void cancel(VideoGenJob job) {
        if (job.getLlmGatewayJobId() == null) {
            return;
        }
        llmGatewayClient.cancel(TenantContextHolder.get().tenantId().toString(), java.util.UUID.fromString(job.getLlmGatewayJobId()));
    }
}
