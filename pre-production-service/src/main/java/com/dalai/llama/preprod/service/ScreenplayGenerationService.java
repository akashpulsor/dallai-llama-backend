package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.GenerationSource;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.ScreenplaySceneCharacter;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.dto.SaveScreenplayEditRequest;
import com.dalai.llama.preprod.dto.SceneCharacterView;
import com.dalai.llama.preprod.dto.ScreenplaySceneView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneCharacterRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ScreenplayGenerationResult;
import com.dalai.llama.preprod.service.generation.TolerantEnumParser;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Screenplay is versioned, not one-row-per-project: every {@link #generate} call and every
 * {@link #saveEdit} call inserts a NEW {@code Screenplay} row with the next version number
 * rather than overwriting the previous one. {@link #get} returns the latest version by default;
 * {@link #listVersions} and {@link #getVersion} are what a version-navigation UI ("next
 * version" / "previous version") reads. Source (GENERATED vs EDITED) and parentId track lineage
 * the same way creative-planning-service's {@code idea_option} table does for idea candidates.
 */
@Service
public class ScreenplayGenerationService {

    private static final String TASK_KEY = "PRE_PROD_SCREENPLAY_GENERATE";

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final ScreenplayRepository screenplayRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final ScreenplaySceneCharacterRepository screenplaySceneCharacterRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String defaultModel;

    public ScreenplayGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            ScreenplayRepository screenplayRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            ScreenplaySceneCharacterRepository screenplaySceneCharacterRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.screenplayRepository = screenplayRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.screenplaySceneCharacterRepository = screenplaySceneCharacterRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ScreenplayView generate(UUID tenantId, UUID projectId) {
        return generate(tenantId, projectId, null);
    }

    /** Powers a change request's "apply" -- same generate() flow, with the requested change
     * folded into the script text the LLM sees, rather than a separate prompt/task key. */
    @Transactional
    public ScreenplayView regenerateWithNote(UUID tenantId, UUID projectId, String note) {
        return generate(tenantId, projectId, note);
    }

    private ScreenplayView generate(UUID tenantId, UUID projectId, String note) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        String scriptText = (note == null || note.isBlank())
                ? script.getScriptText()
                : script.getScriptText() + "\n\nRequested change for this screenplay: " + note;

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "screenplay-generate-" + projectId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("scriptText", scriptText)).withProjectId(projectId));

        ScreenplayGenerationResult parsed = parse(response);
        if (parsed.scenes() == null || parsed.scenes().isEmpty()) {
            throw PreProductionException.upstream("PRE_PROD_SCREENPLAY_GENERATE returned no scenes");
        }

        Screenplay screenplay = newVersion(tenantId, projectId, script.getId(), GenerationSource.GENERATED, null, project.getLockedIdeaId());
        Map<String, ScriptCharacter> charactersByKey = scriptCharacterRepository.findByScriptId(script.getId()).stream()
                .collect(Collectors.toMap(ScriptCharacter::getCharacterKey, c -> c, (a, b) -> a));
        List<ScreenplayScene> scenes = new java.util.ArrayList<>();
        for (ScreenplayGenerationResult.SceneItem item : parsed.scenes()) {
            ScreenplayScene scene = screenplaySceneRepository.save(ScreenplayScene.builder()
                    .tenantId(tenantId)
                    .screenplayId(screenplay.getId())
                    .sceneNumber(item.sceneNumber())
                    .slug(item.slug())
                    .location(item.location())
                    .timeOfDay(TolerantEnumParser.parse(TimeOfDay.class, item.timeOfDay(), TimeOfDay.MIDDAY))
                    .summary(item.summary())
                    .characterFocus(item.characterFocus())
                    .emotionalPurpose(item.emotionalPurpose())
                    .estimatedSeconds(item.estimatedSeconds())
                    .createdAt(screenplay.getCreatedAt())
                    .build());
            scenes.add(scene);
            saveSceneCharacters(tenantId, scene, item.characterKeys(), charactersByKey);
        }

        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SCREENPLAY_READY);

        return toView(screenplay, scenes);
    }

    /** {@code characterKeys} not matching a real script character (typo, hallucinated key) are
     * silently dropped rather than failing the whole generation -- a scene missing one character
     * tag is a minor display gap, not worth losing the entire screenplay over. */
    private void saveSceneCharacters(UUID tenantId, ScreenplayScene scene, List<String> characterKeys, Map<String, ScriptCharacter> charactersByKey) {
        if (characterKeys == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        characterKeys.stream()
                .map(charactersByKey::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(character -> screenplaySceneCharacterRepository.save(ScreenplaySceneCharacter.builder()
                        .tenantId(tenantId)
                        .screenplaySceneId(scene.getId())
                        .scriptCharacterId(character.getId())
                        .createdAt(now)
                        .build()));
    }

    /** Saves a creator's manual scene edits as a new EDITED version -- no LLM call, a direct
     * write. {@code parentVersion} is the version the edit started from; the new version becomes
     * {@code parentVersion + 1} (or the current max + 1 if something else was generated since,
     * so two edits never collide on the same version number). */
    @Transactional
    public ScreenplayView saveEdit(UUID tenantId, UUID projectId, Integer parentVersion, SaveScreenplayEditRequest request) {
        Screenplay parent = screenplayRepository.findByProjectIdAndVersion(projectId, parentVersion)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No screenplay version " + parentVersion + " for project " + projectId));

        Screenplay screenplay = newVersion(tenantId, projectId, parent.getScriptId(), GenerationSource.EDITED, parent.getId(), parent.getLockedIdeaId());
        List<ScreenplayScene> scenes = request.scenes().stream()
                .map(item -> ScreenplayScene.builder()
                        .tenantId(tenantId)
                        .screenplayId(screenplay.getId())
                        .sceneNumber(item.sceneNumber())
                        .slug(item.slug())
                        .location(item.location())
                        .timeOfDay(item.timeOfDay() == null ? TimeOfDay.MIDDAY : item.timeOfDay())
                        .summary(item.summary())
                        .characterFocus(item.characterFocus())
                        .emotionalPurpose(item.emotionalPurpose())
                        .estimatedSeconds(item.estimatedSeconds())
                        .createdAt(screenplay.getCreatedAt())
                        .build())
                .map(screenplaySceneRepository::save)
                .collect(Collectors.toList());

        return toView(screenplay, scenes);
    }

    private Screenplay newVersion(UUID tenantId, UUID projectId, UUID scriptId, GenerationSource source, UUID parentId, UUID lockedIdeaId) {
        int nextVersion = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(s -> s.getVersion() + 1)
                .orElse(1);
        OffsetDateTime now = OffsetDateTime.now();
        return screenplayRepository.save(Screenplay.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .scriptId(scriptId)
                .lockedIdeaId(lockedIdeaId)
                .status(DraftStatus.DRAFT)
                .version(nextVersion)
                .source(source)
                .parentId(parentId)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** The latest version -- what a project page reads by default. REQUIRES_NEW for the same
     * reason as {@code ScriptGenerationService#get} -- see its javadoc. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ScreenplayView get(UUID tenantId, UUID projectId) {
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No screenplay for project " + projectId));
        return toView(screenplay, screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId()));
    }

    /** One specific version -- what "previous version" / "next version" navigation reads. */
    @Transactional(readOnly = true)
    public ScreenplayView getVersion(UUID tenantId, UUID projectId, Integer version) {
        Screenplay screenplay = screenplayRepository.findByProjectIdAndVersion(projectId, version)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No screenplay version " + version + " for project " + projectId));
        return toView(screenplay, screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId()));
    }

    /** Every version ever saved for this project, oldest first -- what populates a version
     * picker without fetching every version's full scene list. */
    @Transactional(readOnly = true)
    public List<ScreenplayView> listVersions(UUID tenantId, UUID projectId) {
        return screenplayRepository.findByProjectIdOrderByVersionAsc(projectId).stream()
                .filter(s -> s.getTenantId().equals(tenantId))
                .map(s -> toView(s, List.of()))
                .collect(Collectors.toList());
    }

    private ScreenplayGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for PRE_PROD_SCREENPLAY_GENERATE");
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ScreenplayGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SCREENPLAY_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ScreenplayView toView(Screenplay screenplay, List<ScreenplayScene> scenes) {
        if (scenes.isEmpty()) {
            return new ScreenplayView(screenplay.getId(), screenplay.getProjectId(), screenplay.getScriptId(), screenplay.getLockedIdeaId(),
                    screenplay.getStatus(), screenplay.getVersion(), screenplay.getSource(), screenplay.getParentId(), screenplay.getCreatedAt(), List.of());
        }
        List<UUID> sceneIds = scenes.stream().map(ScreenplayScene::getId).collect(Collectors.toList());
        Map<UUID, ScriptCharacter> charactersById = scriptCharacterRepository.findByScriptId(screenplay.getScriptId()).stream()
                .collect(Collectors.toMap(ScriptCharacter::getId, c -> c, (a, b) -> a));
        Map<UUID, List<SceneCharacterView>> charactersByScene = screenplaySceneCharacterRepository.findByScreenplaySceneIdIn(sceneIds).stream()
                .collect(Collectors.groupingBy(ScreenplaySceneCharacter::getScreenplaySceneId,
                        Collectors.mapping(link -> {
                            ScriptCharacter character = charactersById.get(link.getScriptCharacterId());
                            return character == null ? null
                                    : new SceneCharacterView(character.getId(), character.getCharacterKey(), character.getCharacterName(), character.getCharacterType());
                        }, Collectors.filtering(java.util.Objects::nonNull, Collectors.toList()))));

        List<ScreenplaySceneView> sceneViews = scenes.stream()
                .map(s -> new ScreenplaySceneView(s.getId(), s.getSceneNumber(), s.getSlug(), s.getLocation(), s.getTimeOfDay(),
                        s.getSummary(), s.getCharacterFocus(), s.getEmotionalPurpose(), s.getEstimatedSeconds(),
                        charactersByScene.getOrDefault(s.getId(), List.of())))
                .collect(Collectors.toList());
        return new ScreenplayView(screenplay.getId(), screenplay.getProjectId(), screenplay.getScriptId(), screenplay.getLockedIdeaId(),
                screenplay.getStatus(), screenplay.getVersion(), screenplay.getSource(), screenplay.getParentId(), screenplay.getCreatedAt(), sceneViews);
    }
}
