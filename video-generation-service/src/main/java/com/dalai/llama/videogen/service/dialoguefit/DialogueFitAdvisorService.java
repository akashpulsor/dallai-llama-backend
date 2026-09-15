package com.dalai.llama.videogen.service.dialoguefit;

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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Which of the three remedies suits a shot whose line overruns it -- asked of a model, because the
 * arithmetic cannot know.
 *
 * <p>The maths establishes the facts and the bounds: shot-01-005 carries 12.21 measured seconds of
 * narration in a 5-second clip, closing the gap costs 2.6x, and nothing longer than the ceiling can
 * be generated. What it cannot weigh is whether eight more seconds of that cutaway would still look
 * like the film, whether a closing call-to-action can lose words without losing the brand, or
 * whether this is the shot carrying the scene's point. A fixed rule -- "extend by at most two
 * seconds, otherwise rewrite" -- answers all of those identically, and is therefore wrong about most
 * of them.
 *
 * <p>So the numbers are computed and the judgement is asked for. Note which way round that is: the
 * model is never asked how long anything takes, which is the question it demonstrably cannot answer
 * ({@code VIDEO_DIALOGUE_FIT} judged shot-01-003's line at eighteen seconds against a measured
 * 4.32). It is asked to choose between options it has been given numbers for, which is a different
 * and far more reliable kind of question.
 *
 * <p>Only runs when there is a mismatch. A shot whose line fits its clip -- or falls short of it,
 * where nothing is cut and the shot simply runs on -- is generated without any of this: the check
 * upstream is arithmetic against measured audio and costs nothing.
 *
 * <p>Advisory, and bounded. The recommendation is shown to the creator with its reasoning as the
 * suggested option, and they still choose. Any duration it returns is clamped to what the shot may
 * actually become before it is used: a judgement is not an authorisation to spend.
 *
 * <p>Best-effort. A gateway that is unreachable, or an answer that will not parse, leaves the
 * creator with the three options and no suggestion, which is where they were before this existed.
 */
@Slf4j
@Service
public class DialogueFitAdvisorService {

    private static final String TASK_KEY = "VIDEO_DIALOGUE_FIT_DECISION";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean enabled;

    public DialogueFitAdvisorService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.dialogue-fit.model:gemini-2.5-flash}") String model,
            @Value("${video-gen.dialogue-fit.advice-enabled:true}") boolean enabled
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * @param tenantId  whose gateway route this goes through -- it is a path segment, not a header,
     *                  so it cannot be omitted.
     * @param shotRef   what to call the shot in the reasoning shown to the creator.
     * @param shotType  B_ROLL / DIALOGUE / ACTION / MOTION_GRAPHIC -- the single strongest signal for
     *                  whether spare seconds will read as a held beat or as dead screen time.
     * @param action    what happens on screen, so the judgement is about this shot and not about
     *                  durations in the abstract.
     * @param maxDurationSeconds the longest this shot could be generated at. The recommendation is
     *                  clamped to it; a model cannot authorise spending past the ceiling.
     */
    public Advice advise(UUID tenantId, UUID projectId, String shotRef, String shotType, String action,
                         String dialogue, DialogueFitMath.Report fit, int maxDurationSeconds) {
        if (!enabled || fit == null || !fit.verdict().needsAttention() || dialogue == null || dialogue.isBlank()) {
            return null;
        }
        int planned = fit.plannedDurationSeconds() == null ? 0 : fit.plannedDurationSeconds();
        if (planned <= 0) {
            return null;
        }
        int requiredRounded = (int) Math.ceil(fit.requiredSeconds());
        double costMultiple = requiredRounded / (double) planned;

        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    // The tenant goes in the PATH of the gateway's internal chat route, so a null
                    // here is a request to /tenants/null/chat and comes back 401. Nothing about the
                    // failure says "you forgot the tenant", which is why this was only found by
                    // calling it for real.
                    tenantId == null ? null : tenantId.toString(),
                    "dialogue-fit-advice-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            TASK_KEY,
                            // ofEntries, not of: Map.of stops at ten pairs and this prompt needs
                            // twelve to describe the shot, the measurement and what it would cost.
                            Map.ofEntries(
                                    Map.entry("shotRef", orDash(shotRef)),
                                    Map.entry("shotType", orDash(shotType)),
                                    Map.entry("action", orDash(action)),
                                    Map.entry("dialogue", dialogue),
                                    Map.entry("plannedSeconds", String.valueOf(planned)),
                                    Map.entry("fps", String.valueOf(fit.fps())),
                                    Map.entry("measuredSeconds", fmt(fit.audioSpanSeconds())),
                                    Map.entry("requiredSeconds", fmt(fit.requiredSeconds())),
                                    Map.entry("requiredSecondsRounded", String.valueOf(requiredRounded)),
                                    Map.entry("overrunSeconds", fmt(Math.abs(fit.slackSeconds()))),
                                    Map.entry("maxDurationSeconds", String.valueOf(maxDurationSeconds)),
                                    Map.entry("costMultiple", String.format(Locale.ROOT, "%.1f", costMultiple))),
                            projectId));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(stripFence(response.response()));
            String recommendation = node.path("recommendation").asText("");
            if (!List.of("EXTEND", "REWRITE", "KEEP").contains(recommendation)) {
                log.warn("Fit advice came back with an unusable recommendation {} -- offering none", recommendation);
                return null;
            }
            Integer recommendedDuration = null;
            if ("EXTEND".equals(recommendation)) {
                JsonNode durationNode = node.path("recommendedDurationSeconds");
                int suggested = durationNode.isNumber() ? durationNode.asInt() : requiredRounded;
                // Clamped, always. A recommendation is a judgement about craft, not permission to
                // spend: a model that asks for forty seconds gets the ceiling, and one that asks for
                // less than the shot already is gets left alone.
                recommendedDuration = Math.max(planned, Math.min(maxDurationSeconds, suggested));
            }
            String reason = node.path("reason").isNull() ? null : node.path("reason").asText(null);
            log.info("Fit advice shotRef={} verdict={} recommendation={} duration={}",
                    shotRef, fit.verdict(), recommendation, recommendedDuration);
            return new Advice(recommendation, recommendedDuration, reason);
        } catch (Exception ex) {
            // The creator still has all three options; they just do not have a suggestion.
            log.warn("Could not get fit advice for shot {} -- offering the options without one: {}",
                    shotRef, ex.getMessage());
            return null;
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private static String orDash(String value) {
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

    /**
     * @param recommendation EXTEND / REWRITE / KEEP -- which option to show as suggested.
     * @param recommendedDurationSeconds only set for EXTEND, already clamped to what the shot may
     *                       become.
     * @param reason         one sentence for the creator, saying why this is the right trade for
     *                       this shot. The part that makes the suggestion worth having: a
     *                       recommendation without a reason is just another button.
     */
    public record Advice(String recommendation, Integer recommendedDurationSeconds, String reason) {}
}
