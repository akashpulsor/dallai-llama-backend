package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardScene;
import com.dalai.llama.creator.dto.response.CreatorHistoryItemResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardSceneRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CreatorHistoryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final CreatorIdeaRepository ideaRepository;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorStoryboardRepository storyboardRepository;
    private final CreatorStoryboardSceneRepository sceneRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorProperties properties;

    public CreatorHistoryService(
            CreatorIdeaRepository ideaRepository,
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorStoryboardRepository storyboardRepository,
            CreatorStoryboardSceneRepository sceneRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            CreatorProperties properties
    ) {
        this.ideaRepository = ideaRepository;
        this.scriptRepository = scriptRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.storyboardRepository = storyboardRepository;
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<CreatorHistoryItemResponse> listStorylines(String tenantId, String userId, Integer limit) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        return ideaRepository
                .findStorylineHistory(safeTenantId, safeUserId, PageRequest.of(0, normalizedLimit(limit)))
                .stream()
                .map(idea -> storylineItem(idea, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreatorHistoryItemResponse getStoryline(UUID storyIdeaId, String tenantId, String userId) {
        CreatorIdea idea = ideaRepository
                .findByIdAndTenantIdAndUserId(storyIdeaId, defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Storyline was not found."));
        return storylineItem(idea, true);
    }

    @Transactional(readOnly = true)
    public List<CreatorHistoryItemResponse> listScripts(String tenantId, String userId, Integer limit) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        List<CreatorScript> scripts = scriptRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                safeTenantId,
                safeUserId,
                PageRequest.of(0, normalizedLimit(limit))
        );
        Map<UUID, CreatorIdea> ideasById = loadIdeas(
                scripts.stream().map(CreatorScript::getStoryIdeaId).filter(Objects::nonNull).toList(),
                safeTenantId,
                safeUserId
        );
        return scripts.stream()
                .map(script -> scriptItem(script, ideasById.get(script.getStoryIdeaId()), false))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreatorHistoryItemResponse getScript(UUID scriptId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorScript script = scriptRepository
                .findByIdAndTenantIdAndUserId(scriptId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script was not found."));
        CreatorIdea idea = script.getStoryIdeaId() == null
                ? null
                : ideaRepository.findByIdAndTenantIdAndUserId(script.getStoryIdeaId(), safeTenantId, safeUserId).orElse(null);
        return scriptItem(script, idea, true);
    }

    @Transactional(readOnly = true)
    public List<CreatorHistoryItemResponse> listStoryboards(String tenantId, String userId, Integer limit) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        List<CreatorStoryboard> storyboards = storyboardRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                safeTenantId,
                safeUserId,
                PageRequest.of(0, normalizedLimit(limit))
        );
        Map<UUID, CreatorIdea> ideasById = loadIdeas(
                storyboards.stream().map(CreatorStoryboard::getIdeaId).filter(Objects::nonNull).toList(),
                safeTenantId,
                safeUserId
        );
        return storyboards.stream()
                .map(storyboard -> storyboardItem(storyboard, ideasById.get(storyboard.getIdeaId()), false))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreatorHistoryItemResponse getStoryboard(UUID storyboardId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorStoryboard storyboard = storyboardRepository
                .findByIdAndTenantIdAndUserId(storyboardId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Storyboard was not found."));
        CreatorIdea idea = storyboard.getIdeaId() == null
                ? null
                : ideaRepository.findByIdAndTenantIdAndUserId(storyboard.getIdeaId(), safeTenantId, safeUserId).orElse(null);
        return storyboardItem(storyboard, idea, true);
    }

    private CreatorHistoryItemResponse storylineItem(CreatorIdea idea, boolean includePayload) {
        Map<String, Object> selectionContext = copyMap(idea.getSelectionContext());
        Map<String, Object> storyScript = mapOrEmpty(selectionContext.get("storyScript"));
        String title = firstNonBlank(stringValue(storyScript.get("projectTitle")), idea.getTitle(), "Past storyline");
        String preview = firstNonBlank(
                idea.getScript(),
                stringValue(storyScript.get("storyline")),
                stringValue(storyScript.get("logline")),
                idea.getSummary()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        if (includePayload) {
            payload.put("storyScriptText", defaultString(idea.getScript(), ""));
            payload.put("storyScriptJson", storyScript);
            payload.put("scenes", idea.getScenes() == null ? List.of() : idea.getScenes());
            payload.put("selectionContext", selectionContext);
        }
        return new CreatorHistoryItemResponse(
                idea.getId(),
                "storyline",
                topicFromIdea(idea),
                title,
                "Past Storyline",
                idea.getProjectId(),
                uuidValue(selectionContext.get("parentLockedIdeaId")),
                idea.getId(),
                uuidValue(selectionContext.get("scriptId")),
                null,
                selectedIdeaMap(idea),
                idea.getStatus(),
                idea.getDurationSeconds(),
                null,
                preview(preview),
                payload,
                idea.getCreatedAt(),
                idea.getUpdatedAt()
        );
    }

    private CreatorHistoryItemResponse scriptItem(CreatorScript script, CreatorIdea idea, boolean includePayload) {
        List<CreatorScriptShotPlan> productionPlans = includePayload
                ? shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())
                : List.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (includePayload) {
            payload.put("scriptText", defaultString(script.getScriptText(), ""));
            payload.put("scriptJson", copyMap(script.getScriptPayload()));
            payload.put("shots", script.getShots() == null ? List.of() : script.getShots());
            payload.put("productionPlanTags", productionPlans.stream().map(this::shotPlanMap).toList());
        }
        return new CreatorHistoryItemResponse(
                script.getId(),
                "script",
                idea == null ? script.getTitle() : topicFromIdea(idea),
                defaultString(script.getTitle(), "Past script"),
                "Past Script",
                script.getProjectId(),
                script.getLockedIdeaId(),
                script.getStoryIdeaId(),
                script.getId(),
                null,
                selectedIdeaMap(idea),
                script.getStatus(),
                script.getDurationSeconds(),
                null,
                preview(firstNonBlank(script.getScriptText(), stringValue(copyMap(script.getScriptPayload()).get("logline")), script.getTitle())),
                payload,
                script.getCreatedAt(),
                script.getUpdatedAt()
        );
    }

    private CreatorHistoryItemResponse storyboardItem(CreatorStoryboard storyboard, CreatorIdea idea, boolean includePayload) {
        List<Map<String, Object>> scenes = includePayload ? storyboardScenes(storyboard.getId()) : List.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (includePayload) {
            payload.put("storyboardId", storyboard.getId() == null ? "" : storyboard.getId().toString());
            payload.put("metadata", copyMap(storyboard.getMetadata()));
            payload.put("scenes", scenes);
        }
        return new CreatorHistoryItemResponse(
                storyboard.getId(),
                "storyboard",
                idea == null ? storyboard.getTitle() : topicFromIdea(idea),
                defaultString(storyboard.getTitle(), "Past storyboard"),
                "Storyboards",
                storyboard.getProjectId(),
                null,
                storyboard.getIdeaId(),
                uuidValue(copyMap(storyboard.getMetadata()).get("scriptId")),
                storyboard.getId(),
                selectedIdeaMap(idea),
                storyboard.getStatus(),
                storyboard.getDurationSeconds(),
                storyboard.getTotalShots(),
                preview(firstNonBlank(storyboard.getHookStrategy(), storyboard.getEmotionalArc(), storyboard.getPacingStyle(), storyboard.getTitle())),
                payload,
                storyboard.getCreatedAt(),
                storyboard.getUpdatedAt()
        );
    }

    private List<Map<String, Object>> storyboardScenes(UUID storyboardId) {
        List<CreatorStoryboardScene> scenes = sceneRepository.findByStoryboardIdOrderByShotNumberAsc(storyboardId);
        List<UUID> assetIds = scenes.stream()
                .flatMap(scene -> java.util.stream.Stream.of(
                        scene.getImageAssetId(),
                        uuidValue(scene.getMetadata().get("lightingImageAssetId")),
                        uuidValue(scene.getMetadata().get("cameraPlanImageAssetId"))
                ))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, CreatorAsset> assetsById = assetIds.isEmpty()
                ? Map.of()
                : assetRepository.findAllById(assetIds).stream().collect(Collectors.toMap(CreatorAsset::getId, Function.identity(), (left, right) -> left));
        return scenes.stream().map(scene -> sceneMap(scene, assetsById)).toList();
    }

    private Map<String, Object> sceneMap(CreatorStoryboardScene scene, Map<UUID, CreatorAsset> assetsById) {
        CreatorAsset storyboardAsset = assetsById.get(scene.getImageAssetId());
        CreatorAsset lightingAsset = assetsById.get(uuidValue(scene.getMetadata().get("lightingImageAssetId")));
        CreatorAsset cameraPlanAsset = assetsById.get(uuidValue(scene.getMetadata().get("cameraPlanImageAssetId")));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sceneId", scene.getId() == null ? "" : scene.getId().toString());
        map.put("shotNumber", scene.getShotNumber());
        map.put("title", scene.getTitle());
        map.put("startTime", scene.getStartTime());
        map.put("endTime", scene.getEndTime());
        map.put("durationSeconds", scene.getDurationSeconds());
        map.put("shotType", scene.getShotType());
        map.put("cameraAngle", scene.getCameraAngle());
        map.put("cameraMovement", scene.getCameraMovement());
        map.put("lensSuggestion", scene.getLensSuggestion());
        map.put("fps", scene.getFps());
        map.put("imageAssetId", storyboardAsset == null ? null : storyboardAsset.getId());
        map.put("objectKey", storyboardAsset == null ? null : storyboardAsset.getObjectKey());
        map.put("signedUrl", signedUrlFor(storyboardAsset));
        map.put("lightingImageAssetId", lightingAsset == null ? null : lightingAsset.getId());
        map.put("lightingObjectKey", lightingAsset == null ? null : lightingAsset.getObjectKey());
        map.put("lightingImageUrl", signedUrlFor(lightingAsset));
        map.put("cameraPlanImageAssetId", cameraPlanAsset == null ? null : cameraPlanAsset.getId());
        map.put("cameraPlanObjectKey", cameraPlanAsset == null ? null : cameraPlanAsset.getObjectKey());
        map.put("cameraPlanImageUrl", signedUrlFor(cameraPlanAsset));
        map.put("sketchPrompt", scene.getSketchPrompt());
        map.put("metadata", copyMap(scene.getMetadata()));
        return map;
    }

    private Map<UUID, CreatorIdea> loadIdeas(List<UUID> ids, String tenantId, String userId) {
        List<UUID> uniqueIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        return ideaRepository.findByIdInAndTenantIdAndUserId(uniqueIds, tenantId, userId)
                .stream()
                .collect(Collectors.toMap(CreatorIdea::getId, Function.identity(), (left, right) -> left));
    }

    private Map<String, Object> selectedIdeaMap(CreatorIdea idea) {
        if (idea == null) {
            return Map.of();
        }
        Map<String, Object> selectionContext = copyMap(idea.getSelectionContext());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", idea.getId() == null ? "" : idea.getId().toString());
        map.put("ideaId", idea.getId() == null ? "" : idea.getId().toString());
        map.put("projectId", idea.getProjectId() == null ? "" : idea.getProjectId().toString());
        map.put("lockedIdeaId", stringValue(selectionContext.get("parentLockedIdeaId")));
        map.put("title", defaultString(idea.getTitle(), "Selected idea"));
        map.put("summary", defaultString(idea.getSummary(), ""));
        map.put("description", defaultString(idea.getSummary(), idea.getTitle()));
        map.put("status", defaultString(idea.getStatus(), "DRAFT"));
        map.put("durationSeconds", idea.getDurationSeconds());
        map.put("selectionContext", selectionContext);
        return map;
    }

    private Map<String, Object> shotPlanMap(CreatorScriptShotPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", plan.getId() == null ? "" : plan.getId().toString());
        map.put("id", plan.getId() == null ? "" : plan.getId().toString());
        map.put("shotNumber", plan.getShotNumber());
        map.put("styleKey", plan.getStyleKey());
        map.put("storyboardTag", copyMap(plan.getStoryboardTag()));
        map.put("lightingBuildSheetTag", copyMap(plan.getLightingBuildSheetTag()));
        map.put("cameraPlanSheetTag", copyMap(plan.getCameraPlanSheetTag()));
        map.put("promptRunIds", copyMap(plan.getPromptRunIds()));
        map.put("updatedAt", plan.getUpdatedAt() == null ? null : plan.getUpdatedAt().toString());
        return map;
    }

    private String signedUrlFor(CreatorAsset asset) {
        if (asset == null) {
            return null;
        }
        if (asset.getBucket() == null || asset.getBucket().isBlank() || asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            return asset.getPublicUrl();
        }
        try {
            return assetStorageService.signedUrl(
                    asset.getBucket(),
                    asset.getObjectKey(),
                    Duration.ofSeconds(Math.max(300, Math.min(604800, properties.getStorage().getSignedUrlTtlSeconds())))
            );
        } catch (RuntimeException ex) {
            return asset.getPublicUrl();
        }
    }

    private int normalizedLimit(Integer limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));
    }

    private String topicFromIdea(CreatorIdea idea) {
        return firstNonBlank(idea.getTitle(), idea.getSummary(), "Selected idea");
    }

    private String preview(String value) {
        String text = defaultString(value, "").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() <= 900) {
            return text;
        }
        return text.substring(0, 897).trim() + "...";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Map<String, Object> copyMap(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String text = stringValue(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
