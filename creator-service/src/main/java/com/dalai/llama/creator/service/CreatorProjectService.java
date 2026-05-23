package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAudience;
import com.dalai.llama.creator.domain.entity.CreatorCharacterCastMapping;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.request.CreatorProjectRequest;
import com.dalai.llama.creator.dto.response.CreatorProjectResponse;
import com.dalai.llama.creator.repository.CreatorAudienceRepository;
import com.dalai.llama.creator.repository.CreatorCharacterCastMappingRepository;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorProjectRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorProjectService {

    private static final int DEFAULT_LIMIT = 20;

    private final CreatorProjectRepository projectRepository;
    private final CreatorIdeaRepository ideaRepository;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorAudienceRepository audienceRepository;
    private final CreatorCharacterCastMappingRepository characterCastMappingRepository;

    public CreatorProjectService(
            CreatorProjectRepository projectRepository,
            CreatorIdeaRepository ideaRepository,
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorAudienceRepository audienceRepository,
            CreatorCharacterCastMappingRepository characterCastMappingRepository
    ) {
        this.projectRepository = projectRepository;
        this.ideaRepository = ideaRepository;
        this.scriptRepository = scriptRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.audienceRepository = audienceRepository;
        this.characterCastMappingRepository = characterCastMappingRepository;
    }

    @Transactional(readOnly = true)
    public List<CreatorProjectResponse> listProjects(String tenantId, String userId, Integer limit) {
        int normalizedLimit = Math.max(1, Math.min(50, limit == null ? DEFAULT_LIMIT : limit));
        return projectRepository
                .findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous"),
                        PageRequest.of(0, normalizedLimit)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CreatorProjectResponse getProject(UUID projectId, String tenantId, String userId) {
        CreatorProject project = projectRepository
                .findByIdAndTenantIdAndUserId(
                        projectId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator project was not found."));
        return toResponse(project);
    }

    @Transactional
    public CreatorProjectResponse createProject(CreatorProjectRequest request, String tenantId, String userId) {
        CreatorProject project = CreatorProject.builder()
                .tenantId(defaultString(tenantId, "unknown"))
                .userId(defaultString(userId, "anonymous"))
                .status(defaultString(request == null ? null : request.status(), "DRAFT"))
                .selectedPlatformCode(blankToNull(request == null ? null : request.selectedPlatformCode()))
                .selectedCategoryCode(blankToNull(request == null ? null : request.selectedCategoryCode()))
                .timeframe(defaultString(request == null ? null : request.timeframe(), "LAST_7_DAYS"))
                .countryCode(defaultString(request == null ? null : request.countryCode(), "IN"))
                .durationSeconds(request == null ? null : request.durationSeconds())
                .selectedTrendId(request == null ? null : request.selectedTrendId())
                .selectedAudienceId(request == null ? null : request.selectedAudienceId())
                .selectedProfileId(request == null ? null : request.selectedProfileId())
                .selectedIdeaId(request == null ? null : request.selectedIdeaId())
                .selectedStoryboardId(request == null ? null : request.selectedStoryboardId())
                .preferences(copyMap(request == null ? null : request.preferences()))
                .memorySnapshot(copyMap(request == null ? null : request.memorySnapshot()))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public CreatorProject ensureProjectForLockedIdea(
            UUID requestedProjectId,
            String tenantId,
            String userId,
            String title,
            String summary,
            String source,
            UUID trendId,
            String platformCode,
            String categoryCode,
            String countryCode,
            String timeframe,
            Integer durationSeconds,
            Map<String, Object> selectionContext
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorProject project = requestedProjectId == null
                ? newProject(safeTenantId, safeUserId)
                : projectRepository
                        .findByIdAndTenantIdAndUserId(requestedProjectId, safeTenantId, safeUserId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator project was not found."));

        project.setStatus("LOCKED");
        project.setSelectedTrendId(trendId == null ? project.getSelectedTrendId() : trendId);
        project.setSelectedPlatformCode(firstNonBlank(platformCode, project.getSelectedPlatformCode()));
        project.setSelectedCategoryCode(firstNonBlank(categoryCode, project.getSelectedCategoryCode()));
        project.setCountryCode(firstNonBlank(countryCode, project.getCountryCode(), "IN"));
        project.setTimeframe(firstNonBlank(timeframe, project.getTimeframe(), "LAST_7_DAYS"));
        project.setDurationSeconds(durationSeconds == null ? project.getDurationSeconds() : durationSeconds);

        Map<String, Object> preferences = copyMap(project.getPreferences());
        putIfPresent(preferences, "title", title);
        putIfPresent(preferences, "briefTitle", title);
        putIfPresent(preferences, "sourceType", source);
        putIfPresent(preferences, "summary", summary);
        putIfPresent(preferences, "platformCode", platformCode);
        putIfPresent(preferences, "categoryCode", categoryCode);
        putIfPresent(preferences, "countryCode", countryCode);
        if (durationSeconds != null) {
            preferences.put("durationSeconds", durationSeconds);
        }
        project.setPreferences(preferences);

        Map<String, Object> memory = copyMap(project.getMemorySnapshot());
        Map<String, Object> lockedBrief = new LinkedHashMap<>();
        putIfPresent(lockedBrief, "title", title);
        putIfPresent(lockedBrief, "summary", summary);
        putIfPresent(lockedBrief, "sourceType", source);
        if (trendId != null) {
            lockedBrief.put("trendId", trendId.toString());
        }
        lockedBrief.put("selectionContext", copyMap(selectionContext));
        lockedBrief.put("lockedAt", OffsetDateTime.now().toString());
        memory.put("lockedBrief", lockedBrief);
        project.setMemorySnapshot(memory);
        project.setUpdatedAt(OffsetDateTime.now());

        return projectRepository.save(project);
    }

    @Transactional
    public void markSelectedIdea(CreatorIdea idea) {
        if (idea == null || idea.getProjectId() == null) {
            return;
        }
        projectRepository
                .findByIdAndTenantIdAndUserId(idea.getProjectId(), idea.getTenantId(), idea.getUserId())
                .ifPresent(project -> {
                    project.setSelectedIdeaId(idea.getId());
                    project.setSelectedTrendId(idea.getTrendId() == null ? project.getSelectedTrendId() : idea.getTrendId());
                    project.setDurationSeconds(idea.getDurationSeconds() == null ? project.getDurationSeconds() : idea.getDurationSeconds());
                    project.setStatus(firstNonBlank(idea.getStatus(), project.getStatus(), "LOCKED"));
                    project.setUpdatedAt(OffsetDateTime.now());
                    projectRepository.save(project);
                });
    }

    private CreatorProject newProject(String tenantId, String userId) {
        OffsetDateTime now = OffsetDateTime.now();
        return CreatorProject.builder()
                .tenantId(tenantId)
                .userId(userId)
                .status("DRAFT")
                .timeframe("LAST_7_DAYS")
                .countryCode("IN")
                .preferences(new LinkedHashMap<>())
                .memorySnapshot(new LinkedHashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public CreatorProjectResponse toResponse(CreatorProject project) {
        Map<String, Object> preferences = copyMap(project.getPreferences());
        Map<String, Object> memory = copyMap(project.getMemorySnapshot());
        enrichProjectMemory(project, memory);
        String title = firstNonBlank(
                stringValue(preferences.get("title")),
                stringValue(preferences.get("briefTitle")),
                titleFromMemory(memory),
                "Creator project"
        );
        return new CreatorProjectResponse(
                project.getId(),
                project.getId() == null ? null : project.getId().toString(),
                title,
                project.getStatus(),
                project.getSelectedPlatformCode(),
                project.getSelectedCategoryCode(),
                project.getTimeframe(),
                project.getCountryCode(),
                project.getDurationSeconds(),
                project.getSelectedTrendId(),
                project.getSelectedAudienceId(),
                project.getSelectedProfileId(),
                project.getSelectedIdeaId(),
                project.getSelectedStoryboardId(),
                preferences,
                memory,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private void enrichProjectMemory(CreatorProject project, Map<String, Object> memory) {
        if (project == null || project.getId() == null || memory == null) {
            return;
        }
        memory.put("projectId", project.getId().toString());
        List<CreatorIdea> ideas = new java.util.ArrayList<>(ideaRepository.findTop20ByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(
                project.getId(),
                project.getTenantId(),
                project.getUserId()
        ));
        if (project.getSelectedIdeaId() != null && ideas.stream().noneMatch(idea -> project.getSelectedIdeaId().equals(idea.getId()))) {
            ideaRepository.findByIdAndTenantIdAndUserId(project.getSelectedIdeaId(), project.getTenantId(), project.getUserId())
                    .ifPresent(ideas::add);
        }
        java.util.Optional<CreatorIdea> selectedIdea = java.util.Optional.empty();
        java.util.Optional<CreatorIdea> lockedIdea = java.util.Optional.empty();
        if (!ideas.isEmpty()) {
            List<Map<String, Object>> ideaMaps = ideas.stream().map(this::ideaMap).toList();
            memory.put("storyIdeas", ideaMaps);
            memory.put("ideaCandidates", ideaMaps);
            selectedIdea = selectedIdea(project, ideas);
            lockedIdea = lockedIdea(ideas);
            selectedIdea.ifPresent(idea -> {
                Map<String, Object> selectedMap = ideaMap(idea);
                memory.put("selectedStoryIdea", selectedMap);
                if (!defaultString(idea.getScript(), "").isBlank() || copyMap(idea.getSelectionContext()).containsKey("storyScript")) {
                    Map<String, Object> storyScript = storyScriptMap(idea);
                    memory.put("storyScript", storyScript);
                    memory.put("storyScriptIdea", storyScript);
                }
            });
            lockedIdea.ifPresent(idea -> memory.put("lockedIdea", ideaMap(idea)));
        }

        resolveAudience(project).ifPresent(audience -> {
            Map<String, Object> audienceMap = audienceMap(audience);
            memory.put("audienceDecision", audienceMap);
            memory.put("audience", audienceMap);
            memory.put("confirmedAudience", audienceMap);
        });

        List<Map<String, Object>> characterMappings = resolveCharacterCastMappings(project, selectedIdea, lockedIdea)
                .stream()
                .map(this::characterCastMappingMap)
                .toList();
        if (!characterMappings.isEmpty()) {
            Map<String, Object> castPlan = new LinkedHashMap<>();
            castPlan.put("id", project.getSelectedProfileId() == null ? "" : project.getSelectedProfileId().toString());
            castPlan.put("projectId", project.getId().toString());
            castPlan.put("characterMappings", characterMappings);
            castPlan.put("mappings", characterMappings);
            memory.put("castPlan", castPlan);
            memory.put("characterCastMappings", characterMappings);
            memory.put("castMappings", characterMappings);
        }

        resolveProjectScript(project, ideas).ifPresent(script -> {
            List<Map<String, Object>> productionPlans = shotPlanRepository
                    .findByScriptIdOrderByShotNumberAsc(script.getId())
                    .stream()
                    .map(this::shotPlanMap)
                    .toList();
            Map<String, Object> screenplay = screenplayMap(script, productionPlans);
            memory.put("screenplay", screenplay);
            memory.put("generatedScript", screenplay);
            memory.put("scriptDetail", screenplay);
            memory.put("productionPlanTags", productionPlans);
        });
    }

    private java.util.Optional<CreatorIdea> selectedIdea(CreatorProject project, List<CreatorIdea> ideas) {
        if (project.getSelectedIdeaId() != null) {
            return ideas.stream().filter(idea -> project.getSelectedIdeaId().equals(idea.getId())).findFirst();
        }
        return ideas.stream()
                .filter(idea -> idea.isSaved() || "SCRIPT_GENERATED".equalsIgnoreCase(defaultString(idea.getStatus(), "")) || "SCRIPT_EDITED".equalsIgnoreCase(defaultString(idea.getStatus(), "")))
                .findFirst();
    }

    private java.util.Optional<CreatorIdea> lockedIdea(List<CreatorIdea> ideas) {
        return ideas.stream()
                .filter(idea -> idea.getLockedAt() != null || "LOCKED".equalsIgnoreCase(defaultString(idea.getStatus(), "")))
                .findFirst()
                .or(() -> ideas.stream().reduce((first, second) -> second));
    }

    private Map<String, Object> ideaMap(CreatorIdea idea) {
        Map<String, Object> selectionContext = copyMap(idea.getSelectionContext());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", idea.getId() == null ? "" : idea.getId().toString());
        map.put("ideaId", idea.getId() == null ? "" : idea.getId().toString());
        map.put("projectId", idea.getProjectId() == null ? "" : idea.getProjectId().toString());
        map.put("trendId", idea.getTrendId() == null ? null : idea.getTrendId().toString());
        map.put("title", defaultString(idea.getTitle(), "Creator idea"));
        map.put("description", defaultString(idea.getSummary(), idea.getTitle()));
        map.put("summary", defaultString(idea.getSummary(), ""));
        map.put("script", defaultString(idea.getScript(), ""));
        map.put("scriptText", defaultString(idea.getScript(), ""));
        map.put("storyScriptText", defaultString(idea.getScript(), ""));
        map.put("storyScriptJson", mapOrEmpty(selectionContext.get("storyScript")));
        map.put("scriptId", stringValue(selectionContext.get("scriptId")));
        map.put("scenes", idea.getScenes() == null ? List.of() : idea.getScenes());
        map.put("scriptScenes", idea.getScenes() == null ? List.of() : idea.getScenes());
        map.put("durationSeconds", idea.getDurationSeconds());
        map.put("status", defaultString(idea.getStatus(), "DRAFT"));
        map.put("source", defaultString(idea.getSource(), "ORIGINAL"));
        map.put("saved", idea.isSaved());
        map.put("selectionContext", selectionContext);
        map.put("lockedIdeaId", stringValue(selectionContext.get("parentLockedIdeaId")));
        return map;
    }

    private Map<String, Object> storyScriptMap(CreatorIdea idea) {
        Map<String, Object> map = ideaMap(idea);
        map.put("storyIdeaId", idea.getId() == null ? "" : idea.getId().toString());
        map.put("ideaId", idea.getId() == null ? "" : idea.getId().toString());
        map.put("scriptText", defaultString(idea.getScript(), ""));
        map.put("storyScriptText", defaultString(idea.getScript(), ""));
        map.put("scriptJson", map.get("storyScriptJson"));
        map.put("status", defaultString(idea.getStatus(), "SCRIPT_GENERATED"));
        return map;
    }

    private Map<String, Object> screenplayMap(CreatorScript script, List<Map<String, Object>> productionPlans) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", script.getId() == null ? "" : script.getId().toString());
        map.put("scriptId", script.getId() == null ? "" : script.getId().toString());
        map.put("projectId", script.getProjectId() == null ? "" : script.getProjectId().toString());
        map.put("lockedIdeaId", script.getLockedIdeaId() == null ? "" : script.getLockedIdeaId().toString());
        map.put("storyIdeaId", script.getStoryIdeaId() == null ? "" : script.getStoryIdeaId().toString());
        map.put("ideaId", script.getStoryIdeaId() == null ? "" : script.getStoryIdeaId().toString());
        map.put("title", defaultString(script.getTitle(), "Generated screenplay"));
        map.put("script", defaultString(script.getScriptText(), ""));
        map.put("scriptText", defaultString(script.getScriptText(), ""));
        map.put("scriptJson", copyMap(script.getScriptPayload()));
        map.put("scenes", script.getShots() == null ? List.of() : script.getShots());
        map.put("shots", script.getShots() == null ? List.of() : script.getShots());
        map.put("productionPlanTags", productionPlans == null ? List.of() : productionPlans);
        map.put("productionPlanStatus", productionPlans == null || productionPlans.isEmpty() ? "NOT_STARTED" : "GENERATED");
        map.put("durationSeconds", script.getDurationSeconds());
        map.put("dialogueLanguage", script.getDialogueLanguage());
        map.put("screenType", script.getScreenType());
        map.put("status", defaultString(script.getStatus(), "GENERATED"));
        return map;
    }

    private java.util.Optional<CreatorScript> resolveProjectScript(CreatorProject project, List<CreatorIdea> ideas) {
        java.util.Optional<CreatorScript> byProject = scriptRepository
                .findTopByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(project.getId(), project.getTenantId(), project.getUserId());
        if (byProject.isPresent()) {
            return byProject;
        }

        if (project.getSelectedIdeaId() != null) {
            java.util.Optional<CreatorScript> bySelectedIdeaId = scriptRepository
                    .findTopByStoryIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(project.getSelectedIdeaId(), project.getTenantId(), project.getUserId());
            if (bySelectedIdeaId.isPresent()) {
                return bySelectedIdeaId;
            }
        }

        java.util.Optional<CreatorIdea> selected = selectedIdea(project, ideas);
        if (selected.isPresent() && selected.get().getId() != null) {
            java.util.Optional<CreatorScript> byStoryIdea = scriptRepository
                    .findTopByStoryIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(selected.get().getId(), project.getTenantId(), project.getUserId());
            if (byStoryIdea.isPresent()) {
                return byStoryIdea;
            }
        }

        java.util.Optional<CreatorIdea> locked = lockedIdea(ideas);
        if (locked.isPresent() && locked.get().getId() != null) {
            java.util.Optional<CreatorScript> byLockedIdea = scriptRepository
                    .findTopByLockedIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(locked.get().getId(), project.getTenantId(), project.getUserId());
            if (byLockedIdea.isPresent()) {
                return byLockedIdea;
            }
        }

        for (CreatorIdea idea : ideas) {
            if (idea.getId() == null) {
                continue;
            }
            java.util.Optional<CreatorScript> byIdea = scriptRepository
                    .findTopByStoryIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(idea.getId(), project.getTenantId(), project.getUserId());
            if (byIdea.isPresent()) {
                return byIdea;
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<CreatorAudience> resolveAudience(CreatorProject project) {
        if (project.getSelectedAudienceId() != null) {
            java.util.Optional<CreatorAudience> selected = audienceRepository
                    .findByIdAndTenantIdAndUserId(project.getSelectedAudienceId(), project.getTenantId(), project.getUserId());
            if (selected.isPresent()) {
                return selected;
            }
        }
        return audienceRepository
                .findByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(project.getId(), project.getTenantId(), project.getUserId())
                .stream()
                .filter(CreatorAudience::isConfirmed)
                .findFirst()
                .or(() -> audienceRepository
                        .findByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(project.getId(), project.getTenantId(), project.getUserId())
                        .stream()
                        .findFirst());
    }

    private List<CreatorCharacterCastMapping> resolveCharacterCastMappings(
            CreatorProject project,
            java.util.Optional<CreatorIdea> selectedIdea,
            java.util.Optional<CreatorIdea> lockedIdea
    ) {
        List<CreatorCharacterCastMapping> byProject = characterCastMappingRepository
                .findByTenantIdAndUserIdAndProjectIdOrderByCreatedAtAsc(project.getTenantId(), project.getUserId(), project.getId());
        if (!byProject.isEmpty()) {
            return byProject;
        }
        if (selectedIdea.isPresent() && lockedIdea.isPresent() && selectedIdea.get().getId() != null && lockedIdea.get().getId() != null) {
            return characterCastMappingRepository.findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdOrderByCreatedAtAsc(
                    project.getTenantId(),
                    project.getUserId(),
                    lockedIdea.get().getId(),
                    selectedIdea.get().getId()
            );
        }
        return List.of();
    }

    private Map<String, Object> audienceMap(CreatorAudience audience) {
        Map<String, Object> demographics = copyMap(audience.getDemographics());
        Map<String, Object> psychographics = copyMap(audience.getPsychographics());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", audience.getId() == null ? "" : audience.getId().toString());
        map.put("projectId", audience.getProjectId() == null ? "" : audience.getProjectId().toString());
        map.put("title", defaultString(audience.getTitle(), "Saved audience"));
        map.put("description", stringValue(psychographics.get("description")));
        map.put("gender", stringValue(demographics.get("gender")));
        map.put("ageGroup", stringValue(demographics.get("ageGroup")));
        map.put("location", stringValue(demographics.get("location")));
        map.put("interests", audience.getInterests() == null ? List.of() : audience.getInterests());
        map.put("contentPreference", audience.getContentPreference());
        map.put("aiSuggested", audience.isAiSuggested());
        map.put("confirmed", audience.isConfirmed());
        map.put("demographics", demographics);
        map.put("psychographics", psychographics);
        return map;
    }

    private Map<String, Object> characterCastMappingMap(CreatorCharacterCastMapping mapping) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", mapping.getId() == null ? "" : mapping.getId().toString());
        map.put("projectId", mapping.getProjectId() == null ? "" : mapping.getProjectId().toString());
        map.put("lockedIdeaId", mapping.getLockedIdeaId() == null ? "" : mapping.getLockedIdeaId().toString());
        map.put("storyIdeaId", mapping.getStoryIdeaId() == null ? "" : mapping.getStoryIdeaId().toString());
        map.put("scriptId", mapping.getScriptId() == null ? "" : mapping.getScriptId().toString());
        map.put("scriptCharacterId", mapping.getScriptCharacterId() == null ? "" : mapping.getScriptCharacterId().toString());
        map.put("characterKey", mapping.getCharacterKey());
        map.put("characterName", mapping.getCharacterName());
        map.put("characterRole", mapping.getCharacterRole());
        map.put("castProfileId", mapping.getCastProfileId() == null ? "" : mapping.getCastProfileId().toString());
        map.put("actorId", mapping.getCastProfileId() == null ? "" : mapping.getCastProfileId().toString());
        map.put("castDisplayName", mapping.getCastDisplayName());
        map.put("actorName", mapping.getCastDisplayName());
        map.put("characterPayload", copyMap(mapping.getCharacterPayload()));
        map.put("castPayload", copyMap(mapping.getCastPayload()));
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

    private String titleFromMemory(Map<String, Object> memory) {
        Object lockedBrief = memory.get("lockedBrief");
        if (lockedBrief instanceof Map<?, ?> map) {
            Object title = map.get("title");
            return title == null ? "" : String.valueOf(title);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Map<String, Object> copyMap(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
