package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moving a shot to a different place in the film.
 *
 * <p>The move itself is deliberately the smallest possible operation: it renumbers shots and touches
 * nothing else. Not the script, not the shot descriptions, not a single generated clip. That is the
 * whole design -- a creator reordering their edit is not asking for their plan to be rewritten, and
 * a tool that quietly re-planned underneath them would be unusable for exactly the case it exists
 * for.
 *
 * <p>Generated video follows automatically and needs no work: clips are keyed to a shot, and both the
 * film assembly and the storyboard order by {@code shot_number}. Moving a shot moves its video with
 * it because the video was never ordered independently.
 *
 * <p>What the creator gets instead of automatic re-planning is {@link #assessImpact}, which asks what
 * the move DOES -- to the story, to the shots either side of it, and to anything already generated --
 * and says so before anything happens. Three separate answers because they cost different amounts to
 * put right, and a willingness to say "this is fine", because a tool that always finds a concern is
 * one people learn to click past.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotReorderService {

    private static final String TASK_KEY = "PRE_PROD_SHOT_REORDER_IMPACT";

    private final ShotRepository shotRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;

    /**
     * What moving this shot would do, without moving it.
     *
     * <p>Never throws for a model that is unreachable or answers badly. A creator who cannot get an
     * opinion should still be able to reorder their own film -- the reorder does not depend on this,
     * and blocking an edit because a judgement call failed would be the wrong trade.
     */
    public ReorderImpact assessImpact(UUID tenantId, UUID projectId, UUID shotId, int targetPosition) {
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        Shot moving = shots.stream().filter(shot -> shot.getId().equals(shotId)).findFirst()
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId + " in this project"));
        if (targetPosition == moving.getShotNumber()) {
            return ReorderImpact.unchanged();
        }
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "shot-reorder-impact-" + shotId + "-" + targetPosition,
                    new LlmGatewayChatRequest(
                            null,
                            List.of(new LlmGatewayChatRequest.LlmGatewayMessage("user",
                                    describeMove(shots, moving, targetPosition))),
                            Map.of(),
                            TASK_KEY,
                            null,
                            projectId));
            return parse(response);
        } catch (RuntimeException ex) {
            log.warn("Could not judge the impact of reordering shot {}: {}", shotId, ex.getMessage());
            return ReorderImpact.unavailable();
        }
    }

    /**
     * Puts the shot at {@code targetPosition} and renumbers the rest to close the gap.
     *
     * <p>Only shot_number changes. Everything else -- the script, the descriptions, the clips -- is
     * left exactly as it is, which is what makes this safe to do and safe to undo by moving it back.
     */
    @Transactional
    public List<Shot> reorder(UUID tenantId, UUID projectId, UUID shotId, int targetPosition) {
        List<Shot> shots = new ArrayList<>(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId));
        Shot moving = shots.stream().filter(shot -> shot.getId().equals(shotId)).findFirst()
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId + " in this project"));
        if (targetPosition < 1 || targetPosition > shots.size()) {
            throw PreProductionException.badRequest(
                    "Position must be between 1 and " + shots.size());
        }
        shots.remove(moving);
        shots.add(targetPosition - 1, moving);
        for (int i = 0; i < shots.size(); i++) {
            shots.get(i).setShotNumber(i + 1);
        }
        shotRepository.saveAll(shots);
        log.info("Reordered a shot projectId={} shotId={} to position {}", projectId, shotId, targetPosition);
        return shots;
    }

    /** The list as it stands plus the one proposed move, in the order a reader needs them. */
    private String describeMove(List<Shot> shots, Shot moving, int targetPosition) {
        StringBuilder text = new StringBuilder("CURRENT ORDER\n");
        for (Shot shot : shots) {
            text.append(shot.getShotNumber()).append(". ")
                    .append(shot.getShotRef()).append(" [").append(shot.getShotType()).append("] ")
                    .append(shot.getAction() == null ? "" : shot.getAction().strip());
            if (shot.getScriptLine() != null && !shot.getScriptLine().isBlank()) {
                text.append(" | spoken: ").append(shot.getScriptLine().strip());
            }
            text.append('\n');
        }
        text.append("\nPROPOSED MOVE\nMove ").append(moving.getShotRef())
                .append(" from position ").append(moving.getShotNumber())
                .append(" to position ").append(targetPosition)
                .append(". Everything else keeps its relative order.\n");
        return text.toString();
    }

    private ReorderImpact parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            return ReorderImpact.unavailable();
        }
        try {
            String raw = response.response().trim();
            // Models wrap JSON in a fenced block often enough to be worth handling rather than
            // failing an otherwise good answer over three backticks.
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return ReorderImpact.unavailable();
            }
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            return new ReorderImpact(
                    true,
                    text(node, "verdict", "REVIEW"),
                    text(node, "summary", null),
                    text(node, "storyImpact", null),
                    text(node, "shotPlanImpact", null),
                    text(node, "videoImpact", null),
                    node.path("requiresReplanning").asBoolean(false),
                    text(node, "recommendation", null));
        } catch (Exception ex) {
            log.warn("Could not read the reorder impact answer: {}", ex.getMessage());
            return ReorderImpact.unavailable();
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }

    /**
     * What a move would do, in the three places it can do something.
     *
     * @param available          false when no judgement could be obtained. The page says so rather
     *                           than pretending silence means safety.
     * @param verdict            SAFE / REVIEW / BREAKS.
     * @param requiresReplanning true only when the WRITTEN plan would have to change for the new
     *                           order to make sense. Reordering alone never does; a shot whose text
     *                           says "first" does.
     */
    public record ReorderImpact(boolean available,
                                String verdict,
                                String summary,
                                String storyImpact,
                                String shotPlanImpact,
                                String videoImpact,
                                boolean requiresReplanning,
                                String recommendation) {

        static ReorderImpact unavailable() {
            return new ReorderImpact(false, null,
                    "Could not check what this move would do — you can still make it.",
                    null, null, null, false, null);
        }

        static ReorderImpact unchanged() {
            return new ReorderImpact(true, "SAFE", "The shot is already in that position.",
                    null, null, null, false, null);
        }
    }
}
