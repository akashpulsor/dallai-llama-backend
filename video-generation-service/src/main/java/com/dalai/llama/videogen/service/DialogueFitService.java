package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes a shot's dialogue sayable in the time the shot runs for, before anything is generated.
 *
 * <p>Generation is multimodal: the model performs the line rather than having it laid over
 * afterwards, so a line that takes longer to say than the shot lasts comes back cut off mid-word.
 * Both obvious repairs are wrong. Stretching the shot means a client who bought sixty seconds is
 * charged for ninety because the writing ran long. Hurrying the delivery means speech nobody can
 * follow.
 *
 * <p>So it is fixed in the plan instead. This runs at prepare time, where a prompt call costs a
 * fraction of a render, and a shot only reaches dispatch once its line and its duration agree --
 * which is the point of planning at all: not to spend money discovering the problem.
 *
 * <p>Two seconds of overrun are left alone. A shot absorbs that much without anyone noticing, and
 * rewriting a creator's words to save two seconds is the worse trade. Beyond that the line is
 * shortened with its meaning intact, and the creator is told. It is meant to be rare.
 *
 * <p><b>Off by default now.</b> This used to rewrite the line and save it back to the shot on its
 * own, during a prepare the creator had asked for for other reasons -- so the words that came out of
 * a render could differ from the words they wrote, without their having agreed to it. The line is
 * the product, and the fit problem is now surfaced before generation instead:
 * {@code DialogueFitReportService} reports it with the numbers, and the creator either resizes the
 * shot or asks {@code DialogueRetimeService} for a rewrite and reads it before accepting. Set
 * {@code video-gen.dialogue-fit.auto-apply=true} to restore the old behaviour for a project that
 * would rather never be interrupted.
 *
 * <p>Note also that shortening was only ever half the problem, and the rarer half. A line too SHORT
 * for its shot leaves the character standing in silence for the rest of the clip, and nothing here
 * or downstream could see it -- see {@code DialogueFitReportService}.
 *
 * <p>Best-effort throughout: the gateway being unreachable, or answering with something
 * unparseable, leaves the line exactly as written. Generating a shot with dialogue that may run
 * long is better than not generating it.
 */
@Slf4j
@Service
public class DialogueFitService {

    private static final String TASK_KEY = "VIDEO_DIALOGUE_FIT";

    private final LlmGatewayClient llmGatewayClient;
    private final PreProductionServiceClient preProductionServiceClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean enabled;

    public DialogueFitService(
            LlmGatewayClient llmGatewayClient,
            PreProductionServiceClient preProductionServiceClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.dialogue-fit.model:gemini-2.5-flash}") String model,
            @Value("${video-gen.dialogue-fit.auto-apply:false}") boolean enabled
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.preProductionServiceClient = preProductionServiceClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.enabled = enabled;
    }

    /** The shot context to generate from, with its dialogue shortened only if it had to be. */
    public ShotContext fitDialogue(UUID tenantId, UUID projectId, UUID shotId, ShotContext shotContext) {
        if (!enabled || shotContext == null) {
            return shotContext;
        }
        Narrative narrative = shotContext.narrative();
        Integer duration = shotContext.technical() == null ? null : shotContext.technical().durationSeconds();
        if (narrative == null || duration == null || duration <= 0) {
            return shotContext;
        }
        String dialogue = narrative.dialogue();
        if (dialogue == null || dialogue.isBlank()) {
            return shotContext;
        }

        Fit fit = askGateway(tenantId, projectId, dialogue, duration, shotContext.technical().fps());
        if (fit == null || fit.fits() || fit.fitted() == null || fit.fitted().isBlank()) {
            return shotContext;
        }

        log.info("Shortened dialogue to fit its shot projectId={} shotId={} duration={}s estimated={}s",
                projectId, shotId, duration, fit.estimatedSeconds());
        // Onto the shot first, then generate from it. The shot is the line of record; a prompt
        // built from text the shot does not hold is a shot the creator cannot reason about.
        if (tenantId != null && shotId != null) {
            preProductionServiceClient.saveShotVoiceOver(tenantId, shotId, fit.fitted());
        }
        return shotContext.withNarrative(new Narrative(
                narrative.scriptLine(), narrative.screenplaySlug(), narrative.arcPosition(), fit.fitted()));
    }

    private Fit askGateway(UUID tenantId, UUID projectId, String dialogue, int durationSeconds, Integer fps) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    // Was null, which is /tenants/null/chat and a 401 -- so this call could never
                    // have succeeded. It went unnoticed because the failure is swallowed by design
                    // (the line stands as written) and because auto-apply is off by default.
                    tenantId == null ? null : tenantId.toString(),
                    "dialogue-fit-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            TASK_KEY,
                            // fps alongside duration: the plan says how long the shot runs and how
                            // that time is cut, and a line has to be written for both. "unspecified"
                            // rather than a guessed default -- an absent fps is the plan having no
                            // opinion, and inventing one would put a number in the prompt that no
                            // one chose.
                            Map.of("dialogue", dialogue,
                                    "durationSeconds", String.valueOf(durationSeconds),
                                    "fps", fps == null ? "unspecified" : String.valueOf(fps)),
                            projectId));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(stripFence(response.response()));
            return new Fit(
                    node.path("fits").asBoolean(true),
                    node.path("estimatedSeconds").asDouble(-1),
                    node.path("fitted").isNull() ? null : node.path("fitted").asText(null));
        } catch (Exception ex) {
            // The line stands as written. A shot that may run long still beats no shot.
            log.warn("Could not check dialogue against shot duration -- keeping the line as written: {}",
                    ex.getMessage());
            return null;
        }
    }

    /** Models wrap JSON in a ```json fence often enough to be worth handling rather than failing. */
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

    private record Fit(boolean fits, double estimatedSeconds, String fitted) {}
}
