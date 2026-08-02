package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorTrend;
import com.dalai.llama.creator.dto.request.LockIdeaSelectionRequest;
import com.dalai.llama.creator.dto.request.CampaignAngleSelectionRequest;
import com.dalai.llama.creator.dto.response.LockedIdeaSelectionResponse;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorTrendRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LockedIdeaSelectionService {

    private static final int DEFAULT_DURATION_SECONDS = 30;

    private final CreatorIdeaRepository ideaRepository;
    private final CreatorTrendRepository trendRepository;
    private final CreatorProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    public LockedIdeaSelectionService(
            CreatorIdeaRepository ideaRepository,
            CreatorTrendRepository trendRepository,
            CreatorProjectService projectService,
            JdbcTemplate jdbcTemplate
    ) {
        this.ideaRepository = ideaRepository;
        this.trendRepository = trendRepository;
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LockedIdeaSelectionResponse lockSelection(
            LockIdeaSelectionRequest request,
            String tenantId,
            String userId
    ) {
        String source = normalizeSource(request.sourceType());
        if ("ORIGINAL".equals(source) && wordCount(request.ideaText()) > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Original idea must be 50 words or fewer.");
        }

        CreatorTrend trend = null;
        if (request.trendId() != null) {
            trend = trendRepository.findById(request.trendId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected trend was not found."));
        }

        OffsetDateTime now = OffsetDateTime.now();
        String title = truncate(defaultString(request.ideaTitle(), trend == null ? "Locked idea" : trend.getTitle()), 240);
        String summary = defaultString(request.ideaText(), trend == null ? title : trend.getSummary());
        int durationSeconds = normalizeDuration(request.durationSeconds());
        Map<String, Object> selectionContext = buildSelectionContext(request, source, trend, durationSeconds, now);
        CreatorProject project = projectService.ensureProjectForLockedIdea(
                request.projectId(),
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                title,
                summary,
                source,
                trend == null ? request.trendId() : trend.getId(),
                request.platformCode(),
                request.categoryCode(),
                request.countryCode(),
                request.timeframe(),
                durationSeconds,
                selectionContext
        );

        CreatorIdea idea = ideaRepository.save(CreatorIdea.builder()
                .tenantId(defaultString(tenantId, "unknown"))
                .userId(defaultString(userId, "anonymous"))
                .projectId(project.getId())
                .trendId(trend == null ? request.trendId() : trend.getId())
                .source(source)
                .title(title)
                .summary(summary)
                .durationSeconds(durationSeconds)
                .status("LOCKED")
                .selectionContext(selectionContext)
                .lockedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        projectService.markSelectedIdea(idea);
        linkProjectSelection(idea);
        return toResponse(idea);
    }

    @Transactional
    public LockedIdeaSelectionResponse selectCampaignAngle(
            UUID lockedIdeaId,
            CampaignAngleSelectionRequest request,
            String tenantId,
            String userId
    ) {
        if (request == null || request.campaignAngle() == null || request.campaignAngle().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A campaign angle is required.");
        }
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = ideaRepository.findByIdAndTenantIdAndUserId(lockedIdeaId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved brief was not found."));
        if (!"LOCKED".equalsIgnoreCase(lockedIdea.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campaign angles can only be selected on a saved brief.");
        }

        Map<String, Object> angle = new LinkedHashMap<>(request.campaignAngle());
        Map<String, Object> context = new LinkedHashMap<>(lockedIdea.getSelectionContext() == null ? Map.of() : lockedIdea.getSelectionContext());
        Map<String, Object> selectionPayload = mapValue(context.get("selectionPayload"));
        selectionPayload.put("campaignAngle", angle);
        Map<String, Object> selectedIdea = mapValue(selectionPayload.get("idea"));
        if (!selectedIdea.isEmpty()) {
            selectedIdea.put("campaignAngle", angle);
            selectionPayload.put("idea", selectedIdea);
        }
        Map<String, Object> productBrief = mapValue(selectionPayload.get("productIntelligenceBrief"));
        if (!productBrief.isEmpty()) {
            productBrief.put("campaignAngle", angle);
            selectionPayload.put("productIntelligenceBrief", productBrief);
        }
        context.put("selectionPayload", selectionPayload);
        context.put("campaignAngle", angle);
        context.put("campaignAngleSelectedAt", OffsetDateTime.now().toString());
        lockedIdea.setSelectionContext(context);

        return toResponse(ideaRepository.save(lockedIdea));
    }

    private String normalizeSource(String sourceType) {
        String source = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        if ("TREND".equals(source) || "ORIGINAL".equals(source)) {
            return source;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceType must be TREND or ORIGINAL.");
    }

    private int normalizeDuration(Integer durationSeconds) {
        if (durationSeconds == null) {
            return DEFAULT_DURATION_SECONDS;
        }
        if (durationSeconds == 30 || durationSeconds == 45 || durationSeconds == 60) {
            return durationSeconds;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationSeconds must be 30, 45, or 60.");
    }

    private Map<String, Object> buildSelectionContext(
            LockIdeaSelectionRequest request,
            String source,
            CreatorTrend trend,
            int durationSeconds,
            OffsetDateTime lockedAt
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sourceType", source);
        if ("TREND".equals(source)) {
            putIfPresent(context, "platformCode", request.platformCode());
            putIfPresent(context, "categoryCode", request.categoryCode());
            putIfPresent(context, "countryCode", request.countryCode());
            putIfPresent(context, "timeframe", request.timeframe());
        }
        context.put("durationSeconds", durationSeconds);
        context.put("lockedAt", lockedAt.toString());
        Map<String, Object> selectionPayload = request.selectionPayload() == null ? Map.of() : request.selectionPayload();
        context.put("selectionPayload", selectionPayload);
        promoteSelectionPayloadValue(context, selectionPayload, "topicType");
        promoteSelectionPayloadValue(context, selectionPayload, "dialogueLanguage");
        promoteSelectionPayloadValue(context, selectionPayload, "screenType");
        promoteSelectionPayloadValue(context, selectionPayload, "storytellingType");
        promoteSelectionPayloadValue(context, selectionPayload, "hookLens");
        promoteSelectionPayloadValue(context, selectionPayload, "productionStyle");
        promoteSelectionPayloadValue(context, selectionPayload, "hybridSceneMode");
        promoteSelectionPayloadValue(context, selectionPayload, "brollStyle");
        promoteSelectionPayloadValue(context, selectionPayload, "captionStyle");
        promoteSelectionPayloadValue(context, selectionPayload, "productionStyleGuidance");
        promoteSelectionPayloadValue(context, selectionPayload, "screenplayVideoGenerationPackage");
        promoteSelectionPayloadValue(context, selectionPayload, "briefMode");
        promoteSelectionPayloadValue(context, selectionPayload, "marketingAgentMode");
        promoteSelectionPayloadValue(context, selectionPayload, "productInputKey");
        promoteSelectionPayloadValue(context, selectionPayload, "productIntelligenceBrief");
        promoteSelectionPayloadValue(context, selectionPayload, "productUnderstanding");
        promoteSelectionPayloadValue(context, selectionPayload, "adConceptLanes");
        promoteSelectionPayloadValue(context, selectionPayload, "brandContext");
        promoteSelectionPayloadValue(context, selectionPayload, "campaignObjective");
        promoteSelectionPayloadValue(context, selectionPayload, "campaignAngle");
        if (trend != null) {
            context.put("trend", trendSnapshot(trend));
        }
        return context;
    }

    private void promoteSelectionPayloadValue(Map<String, Object> target, Map<String, Object> selectionPayload, String key) {
        Object value = firstSelectionPayloadValue(selectionPayload, key);
        if (hasValue(value)) {
            target.put(key, value);
        }
    }

    private Object firstSelectionPayloadValue(Map<String, Object> selectionPayload, String key) {
        if (selectionPayload == null || selectionPayload.isEmpty()) {
            return null;
        }
        Object value = selectionPayload.get(key);
        if (hasValue(value)) {
            return value;
        }
        Map<String, Object> idea = mapValue(selectionPayload.get("idea"));
        value = idea.get(key);
        if (hasValue(value)) {
            return value;
        }
        Map<String, Object> packagePayload = mapValue(selectionPayload.get("screenplayVideoGenerationPackage"));
        value = packagePayload.get(key);
        return hasValue(value) ? value : null;
    }

    private Map<String, Object> trendSnapshot(CreatorTrend trend) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", trend.getId());
        snapshot.put("title", trend.getTitle());
        snapshot.put("summary", trend.getSummary());
        snapshot.put("platformCode", trend.getPlatformCode());
        snapshot.put("categoryCode", trend.getCategoryCode());
        snapshot.put("countryCode", trend.getCountryCode());
        snapshot.put("score", trend.getScore());
        snapshot.put("velocity", trend.getVelocity());
        snapshot.put("tags", trend.getTags());
        snapshot.put("sourceName", trend.getSourceName());
        snapshot.put("lastSeenAt", trend.getLastSeenAt());
        return snapshot;
    }

    private void linkProjectSelection(CreatorIdea idea) {
        UUID projectId = idea.getProjectId();
        if (projectId == null) {
            return;
        }
        jdbcTemplate.update(
                """
                update creator_projects
                   set selected_idea_id = ?,
                       selected_trend_id = coalesce(?, selected_trend_id),
                       selected_platform_code = coalesce(?, selected_platform_code),
                       selected_category_code = coalesce(?, selected_category_code),
                       country_code = coalesce(?, country_code),
                       duration_seconds = ?,
                       updated_at = now()
                 where id = ?
                   and tenant_id = ?
                   and user_id = ?
                """,
                idea.getId(),
                idea.getTrendId(),
                stringValue(idea.getSelectionContext().get("platformCode")),
                stringValue(idea.getSelectionContext().get("categoryCode")),
                stringValue(idea.getSelectionContext().get("countryCode")),
                idea.getDurationSeconds(),
                projectId,
                idea.getTenantId(),
                idea.getUserId()
        );
    }

    private LockedIdeaSelectionResponse toResponse(CreatorIdea idea) {
        return new LockedIdeaSelectionResponse(
                idea.getId(),
                idea.getProjectId(),
                idea.getTrendId(),
                idea.getSource(),
                idea.getTitle(),
                idea.getSummary(),
                idea.getDurationSeconds(),
                idea.getStatus(),
                idea.getLockedAt(),
                idea.getSelectionContext()
        );
    }

    private int wordCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.trim().split("\\s+").length;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
