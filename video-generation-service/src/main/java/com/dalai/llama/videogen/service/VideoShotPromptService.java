package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an ordinary shot's video prompt from its composed plan.
 *
 * <p>Every shot except a motion graphic had its prompt COMPOSED: a provider strategy joined the
 * shot's fields with separators. That is reliable, and it is not writing. Fifty cinematography
 * clauses concatenated -- "85mm, shallow depth of field, slow dolly in, ISO 800, shutter 180,
 * halation" -- is a spec sheet, and a video model reads it as a list of words rather than a
 * description of a shot. Everything the creator planned was present and none of it was expressed.
 *
 * <p>So the composed text is the INPUT here, not the output. It already gathers every field in the
 * shape the target provider prefers, which is work worth keeping; this asks a model to write it
 * properly, carrying the lighting, lens and movement direction through as planned and inventing
 * nothing. The anti-invention rule is the whole point: a model asked to improve a brief will add a
 * location or a camera move nobody planned, and the render will faithfully include it.
 *
 * <p>The character budget is passed so the prompt is written TO the model's real limit. A prompt
 * that fits needs no compression afterwards, and compression is a lossy pass that drops exactly
 * the specifics this exists to preserve.
 *
 * <h2>Scope and failure</h2>
 * Motion graphics keep their own writer ({@link
 * com.dalai.llama.videogen.service.dialoguefit.MotionGraphicPromptService}) -- they are described
 * as motion, not cinematography, and that template already does it. Best-effort by design: a
 * gateway that cannot answer, or an answer that comes back empty or over budget, leaves the shot
 * with the composed prompt it would have had anyway. A shot described adequately beats one that
 * cannot be prepared at all.
 */
@Slf4j
@Service
public class VideoShotPromptService {

    private static final String TASK_KEY = "VIDEO_SHOT_PROMPT";

    private final LlmGatewayClient llmGatewayClient;
    private final String model;
    private final boolean enabled;

    public VideoShotPromptService(
            LlmGatewayClient llmGatewayClient,
            @Value("${video-gen.shot-prompt.model:gemini-2.5-flash}") String model,
            @Value("${video-gen.shot-prompt.enabled:true}") boolean enabled
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * The written prompt, or null to leave the shot on its composed one.
     *
     * @param composedPrompt what the provider strategy built -- the plan, already assembled
     * @param maxChars       the target video model's real prompt limit
     */
    public String writePrompt(UUID tenantId, UUID projectId, ShotContext shotContext,
                              String composedPrompt, int maxChars) {
        if (!enabled || shotContext == null || composedPrompt == null || composedPrompt.isBlank()) {
            return null;
        }
        Integer duration = shotContext.technical() == null ? null : shotContext.technical().durationSeconds();

        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId == null ? null : tenantId.toString(),
                    "video-shot-prompt-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            TASK_KEY,
                            Map.of("composedPrompt", composedPrompt,
                                    "maxChars", String.valueOf(maxChars),
                                    "durationSeconds", duration == null ? "unspecified" : String.valueOf(duration),
                                    "shotType", orUnstated(shotContext.sceneType())),
                            projectId));

            String written = response == null || response.response() == null
                    ? null : stripFence(response.response());
            if (written == null || written.isBlank()) {
                log.warn("Shot prompt came back empty shotRef={} -- composing the usual way",
                        shotContext.shotRef());
                return null;
            }
            // Over budget is a failure, not something to hand on: the compression pass would then
            // rewrite it, which is the lossy step this call exists to avoid. The composed prompt
            // is the honest fallback -- it goes through that same pass, as it always did.
            if (written.length() > maxChars) {
                log.warn("Written shot prompt is over budget shotRef={} chars={} maxChars={} -- composing the usual way",
                        shotContext.shotRef(), written.length(), maxChars);
                return null;
            }
            log.info("Wrote a shot prompt shotRef={} chars={} composedChars={} maxChars={}",
                    shotContext.shotRef(), written.length(), composedPrompt.length(), maxChars);
            return written;
        } catch (Exception ex) {
            log.warn("Could not write a shot prompt shotRef={} -- composing the usual way: {}",
                    shotContext.shotRef(), ex.getMessage());
            return null;
        }
    }

    private static String orUnstated(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }

    /** Models wrap prose in a fence often enough to be worth undoing; same handling as the
     * motion-graphic writer. */
    private String stripFence(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstNewline < 0 || lastFence <= firstNewline
                ? trimmed
                : trimmed.substring(firstNewline + 1, lastFence).strip();
    }
}
