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


    /**
     * Appended to every video generation's negative prompt.
     *
     * <p>Generation is multimodal -- the model performs the dialogue rather than having it laid
     * over afterwards -- so how the line is delivered is decided here or not at all. Two failures
     * matter and they pull against each other: a line that runs past the shot gets cut off
     * mid-word, and a line hurried to fit stops being intelligible. Naming only the first invites
     * the second, so both are named.
     *
     * <p>Appended rather than replacing what the prompt-format library returns: that carries the
     * shot's own visual negatives, which are still wanted. A model that ignores these is no worse
     * off than before -- this cannot make delivery worse, only better.
     */
    private static final String SPEECH_NEGATIVES = String.join(", ",
            "speech cut off mid-word",
            "dialogue truncated before the line finishes",
            "audio ending abruptly while the speaker is still talking",
            "rushed or sped-up delivery",
            "unnaturally fast speech",
            "garbled or slurred words",
            "muffled, inaudible or unclear voice",
            "mumbling");

    public LlmGatewayVideoGenDispatchService(LlmGatewayClient llmGatewayClient) {
        this.llmGatewayClient = llmGatewayClient;
    }


    /** The shot's own negatives plus the speech ones, or just the speech ones when a shot has
     * none of its own -- the delivery constraints apply to every shot that speaks, and a shot
     * without visual negatives is not a shot that may be cut off mid-sentence. */
    private String withSpeechNegatives(String shotNegatives) {
        if (shotNegatives == null || shotNegatives.isBlank()) {
            return SPEECH_NEGATIVES;
        }
        return shotNegatives.strip().endsWith(",")
                ? shotNegatives.strip() + " " + SPEECH_NEGATIVES
                : shotNegatives.strip() + ", " + SPEECH_NEGATIVES;
    }

    @Override
    public DispatchResult dispatch(VideoGenJob job, String positivePrompt, String negativePrompt, VideoDispatchParams params) {
        String tenantId = job.getTenantId().toString();
        Map<String, Object> videoParams = new LinkedHashMap<>();
        videoParams.put("negative_prompt", withSpeechNegatives(negativePrompt));
        if (params != null && params.durationSeconds() != null) {
            videoParams.put("duration_seconds", params.durationSeconds());
        }
        if (params != null && params.aspectRatio() != null) {
            videoParams.put("aspect_ratio", params.aspectRatio());
        }
        if (params != null && params.generateAudio() != null) {
            videoParams.put("generate_audio", params.generateAudio());
        }
        // FalAiProvider already reads this key for both Seedance and Wan (falls back to its own
        // cost-policy default when absent) -- this was the missing link, nothing on the
        // llm-gateway side needed to change.
        if (params != null && params.resolution() != null) {
            videoParams.put("resolution", params.resolution());
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
