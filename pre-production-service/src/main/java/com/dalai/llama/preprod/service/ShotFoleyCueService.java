package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotFoleyCue;
import com.dalai.llama.preprod.dto.ShotFoleyCueView;
import com.dalai.llama.preprod.repository.ShotFoleyCueRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Derives a shot's foley cue sheet -- what it sounds like, and when -- once, when the shot is
 * planned.
 *
 * <p>This used to live in video-generation-service's prepare, which meant paying for the same
 * derivation on every prepare of the same shot. A cue sheet describes the shot's sound design,
 * which does not change because someone re-prepared it; it belongs with the shot's other plan
 * rows (lighting plan, camera plan, dialogue beats) and travels to video-gen in the prepare
 * bundle. Prepare now reads cues rather than deriving them.
 */
@Slf4j
@Service
public class ShotFoleyCueService {

    private static final String TASK_KEY = "FOLEY_CUE_DERIVATION";
    private static final int DEFAULT_DURATION_MS = 5000;

    private final ShotRepository shotRepository;
    private final ShotFoleyCueRepository shotFoleyCueRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ShotFoleyCueService(
            ShotRepository shotRepository,
            ShotFoleyCueRepository shotFoleyCueRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.shotFoleyCueRepository = shotFoleyCueRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** Derives and stores this shot's cues, replacing any it already had. Idempotent by replace,
     * so a re-plan after the creator edits the shot's sound design produces a fresh sheet rather
     * than appending to a stale one. */
    @Transactional
    public List<ShotFoleyCueView> generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "foley-cue-" + shotId,
                new LlmGatewayChatRequest(
                        defaultModel,
                        List.of(new LlmGatewayMessage("user", describeShot(shot))),
                        JsonExtraction.JSON_MODE_PARAMS,
                        TASK_KEY,
                        Map.of(),
                        shot.getProjectId()
                )
        );

        List<DerivedCue> derived = parse(response);
        shotFoleyCueRepository.deleteByShotId(shotId);
        if (derived.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<ShotFoleyCue> rows = derived.stream()
                .map(cue -> ShotFoleyCue.builder()
                        .tenantId(tenantId)
                        .shotId(shotId)
                        .timestampMs(cue.timestampMs() == null ? 0 : cue.timestampMs())
                        .cueType(cue.cueType() == null || cue.cueType().isBlank() ? "AMBIENT" : cue.cueType())
                        .description(cue.description())
                        .createdAt(now)
                        .build())
                .toList();
        return shotFoleyCueRepository.saveAll(rows).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<ShotFoleyCueView> get(UUID shotId) {
        return shotFoleyCueRepository.findByShotIdOrderByTimestampMsAsc(shotId).stream()
                .map(this::toView)
                .toList();
    }

    /** One query for every shot in the project, grouped in memory -- the bundle exists to avoid
     * a per-shot fan-out, so it must not reintroduce one here. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ShotFoleyCueView>> listByShotIds(List<UUID> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ShotFoleyCueView>> byShot = new LinkedHashMap<>();
        for (ShotFoleyCue cue : shotFoleyCueRepository.findByShotIdInOrderByTimestampMsAsc(shotIds)) {
            byShot.computeIfAbsent(cue.getShotId(), key -> new ArrayList<>()).add(toView(cue));
        }
        return byShot;
    }

    private ShotFoleyCueView toView(ShotFoleyCue cue) {
        return new ShotFoleyCueView(cue.getTimestampMs(), cue.getCueType(), cue.getDescription());
    }

    private List<DerivedCue> parse(LlmGatewayChatResponse response) {
        String json = response == null ? null : response.response();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(JsonExtraction.stripCodeFence(json), DerivedCue[].class));
        } catch (Exception ex) {
            // A cue sheet is metadata, not a blocking dependency -- a shot with no cues still
            // plans, prepares and generates.
            log.warn("Could not parse foley cue JSON, leaving this shot without a cue sheet: {}", ex.getMessage());
            return List.of();
        }
    }

    private String describeShot(Shot shot) {
        int durationMs = shot.getDurationSeconds() == null
                ? DEFAULT_DURATION_MS
                : shot.getDurationSeconds() * 1000;
        StringBuilder description = new StringBuilder();
        description.append("Duration: ").append(durationMs).append("ms\n");
        append(description, "Script line", shot.getScriptLine());
        append(description, "Action", shot.getAction());
        append(description, "Location", shot.getLocation());
        append(description, "Sound design", shot.getSoundDesign());
        append(description, "Body language", shot.getBodyLanguage());
        if (shot.getTimeOfDay() != null) {
            description.append("Time of day: ").append(shot.getTimeOfDay()).append("\n");
        }
        return description.toString();
    }

    private void append(StringBuilder target, String label, String value) {
        if (value != null && !value.isBlank()) {
            target.append(label).append(": ").append(value).append("\n");
        }
    }

    /** Shape of the JSON llm-gateway's FOLEY_CUE_DERIVATION task returns. */
    private record DerivedCue(Integer timestampMs, String cueType, String description) {}
}
