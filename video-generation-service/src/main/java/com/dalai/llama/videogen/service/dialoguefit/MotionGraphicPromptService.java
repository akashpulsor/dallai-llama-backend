package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.dto.shotcontext.MotionGraphic;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the prompt for a motion-graphic shot, from the plan pre-production made for it.
 *
 * <p>A motion graphic was being described to the video model in the vocabulary of live action. The
 * prompt for shot-01-007 -- an interface reveal with no camera, no cast and no location -- was
 * assembled from camera angle, lens, lighting mood and three characters' continuity notes, and said
 * nothing about what moves. The field that does say, {@code animationNotes}, was not carried into
 * the request at all.
 *
 * <p>A bigger template would not have fixed it. Composing joins fields; it cannot read "the screen
 * transitions smoothly to reveal a stylised report card, then two content cards slide in from
 * opposite sides" and turn it into a description of what the model should do to the still it has
 * been handed, in the seconds available. That is writing, so it is asked for.
 *
 * <h2>Scope</h2>
 * Only a shot carrying a motion-graphic plan reaches this -- {@code ShotContext.isPlannedMotionGraphic()},
 * which is null for every other shot type. Nothing else in the prepare path changes shape, and a
 * live-action shot composes its prompt exactly as it always has.
 *
 * <p>Best-effort by design: a gateway that cannot answer leaves the shot with the ordinary composed
 * prompt, which is what it had before this existed. A motion graphic described adequately beats one
 * that cannot be prepared at all.
 */
@Slf4j
@Service
public class MotionGraphicPromptService {

    private static final String TASK_KEY = "VIDEO_MOTION_GRAPHIC_PROMPT";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean enabled;

    public MotionGraphicPromptService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.motion-graphic.prompt-model:gemini-2.5-flash}") String model,
            @Value("${video-gen.motion-graphic.prompt-enabled:true}") boolean enabled
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * The written prompt, or null to leave the shot on the ordinary composed one.
     *
     * <p>Null covers every reason not to use this: the shot is not a motion graphic, the feature is
     * off, the plan is empty, or the gateway could not answer. The caller treats all of them the
     * same way, because from its point of view they are the same thing -- no better prompt is
     * available than the one it already built.
     */
    public String writePrompt(UUID tenantId, UUID projectId, ShotContext shotContext) {
        if (!enabled || shotContext == null || !shotContext.isPlannedMotionGraphic()) {
            return null;
        }
        MotionGraphic plan = shotContext.motionGraphic();
        Integer duration = shotContext.technical() == null ? null : shotContext.technical().durationSeconds();
        Integer fps = shotContext.technical() == null ? null : shotContext.technical().fps();

        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId == null ? null : tenantId.toString(),
                    "motion-graphic-prompt-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            TASK_KEY,
                            Map.of("concept", orUnstated(plan.concept()),
                                    "visualStyle", orUnstated(plan.visualStyle()),
                                    // Verbatim, in its own script. It is rendered as glyphs in the
                                    // frame, so anything that "tidies" it changes the deliverable.
                                    "onScreenText", orUnstated(plan.onScreenText()),
                                    "animationNotes", orUnstated(plan.animationNotes()),
                                    "durationSeconds", duration == null ? "unspecified" : String.valueOf(duration),
                                    "fps", fps == null ? "unspecified" : String.valueOf(fps)),
                            projectId));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(stripFence(response.response()));
            String prompt = node.path("prompt").isNull() ? null : node.path("prompt").asText(null);
            if (prompt == null || prompt.isBlank()) {
                log.warn("Motion-graphic prompt came back without a prompt shotRef={} -- composing the usual way",
                        shotContext.shotRef());
                return null;
            }
            log.info("Wrote a motion-graphic prompt shotRef={} duration={}s chars={}",
                    shotContext.shotRef(), duration, prompt.length());
            return prompt.trim();
        } catch (Exception ex) {
            log.warn("Could not write a motion-graphic prompt shotRef={} -- composing the usual way: {}",
                    shotContext.shotRef(), ex.getMessage());
            return null;
        }
    }

    private static String orUnstated(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }

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
