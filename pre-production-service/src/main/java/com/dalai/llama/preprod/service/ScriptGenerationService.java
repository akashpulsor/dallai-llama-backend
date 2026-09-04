package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.GenerationSource;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.ScriptVersion;
import com.dalai.llama.preprod.dto.CreateCastAssignmentRequest;
import com.dalai.llama.preprod.dto.GenerateScriptRequest;
import com.dalai.llama.preprod.dto.SaveScriptEditRequest;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptVersionView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.UpdateScriptCharacterRequest;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ScriptVersionRepository;
import com.dalai.llama.preprod.service.generation.HookBeatPlanResult;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ScriptCritiqueResult;
import com.dalai.llama.preprod.service.generation.ScriptGenerationResult;
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

@Service
public class ScriptGenerationService {

    private static final String TASK_KEY = "PRE_PROD_SCRIPT_GENERATE";
    private static final String HOOK_BEAT_PLAN_TASK_KEY = "PRE_PROD_HOOK_BEAT_PLAN_GENERATE";
    private static final String CRITIC_TASK_KEY = "PRE_PROD_SCRIPT_CRITIC";
    /** Same "up to 3 attempts, keep going only while the critic says FAIL" bound creator-service's
     * real SCRIPT_CRITIC retry loop used. */
    private static final int MAX_GENERATION_ATTEMPTS = 3;

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final ScriptVersionRepository scriptVersionRepository;
    private final CastProfileService castProfileService;
    private final CastAssignmentService castAssignmentService;
    private final ProjectConfigService projectConfigService;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String defaultModel;

    public ScriptGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            ScriptVersionRepository scriptVersionRepository,
            CastProfileService castProfileService,
            CastAssignmentService castAssignmentService,
            ProjectConfigService projectConfigService,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.scriptVersionRepository = scriptVersionRepository;
        this.castProfileService = castProfileService;
        this.castAssignmentService = castAssignmentService;
        this.projectConfigService = projectConfigService;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ScriptView generate(UUID tenantId, UUID projectId, GenerateScriptRequest request) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        List<CastProfile> productProfiles = loadProductProfiles(tenantId, request.productCastProfileIds());
        int durationSeconds = resolveDuration(tenantId, projectId, request.targetDurationSeconds());
        String dialogueLanguage = resolveDialogueLanguage(projectId);
        String productContext = productContextBlock(productProfiles);

        String beatPlan = generateHookBeatPlan(tenantId, projectId, request.briefText(), durationSeconds);
        String briefWithPlan = beatPlan.isBlank() ? request.briefText()
                : request.briefText() + "\n\nAPPROVED BEAT PLAN (write prose from this structure, do not invent a different one):\n" + beatPlan;

        ScriptGenerationResult parsed = null;
        String critiqueFeedback = "";
        List<String> critiqueNotesByAttempt = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            Map<String, String> variables = Map.of(
                    "brief", briefWithPlan + critiqueFeedback,
                    "durationSeconds", String.valueOf(durationSeconds),
                    "productContext", productContext,
                    "dialogueLanguage", dialogueLanguage
            );
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "script-generate-" + projectId + "-attempt" + attempt,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, TASK_KEY, variables).withProjectId(projectId));

            parsed = parse(response);
            if (parsed.scriptText() == null || parsed.scriptText().isBlank()) {
                throw PreProductionException.upstream("PRE_PROD_SCRIPT_GENERATE returned no scriptText");
            }
            if (attempt == MAX_GENERATION_ATTEMPTS) {
                break;
            }
            ScriptCritiqueResult critique = critiqueScript(tenantId, projectId, request.briefText(), parsed);
            if (!critique.isFail()) {
                break;
            }
            String issues = String.join("; ", critique.issues() == null ? List.of() : critique.issues());
            critiqueNotesByAttempt.add("Attempt " + attempt + ": " + issues);
            critiqueFeedback = "\n\nCRITIC FEEDBACK FROM A PRIOR ATTEMPT (fix these issues, do not repeat them): " + issues;
        }
        String critiqueNotes = critiqueNotesByAttempt.isEmpty() ? null : String.join(" | ", critiqueNotesByAttempt);

        OffsetDateTime now = OffsetDateTime.now();
        Script script = scriptRepository.findByProjectId(projectId).orElseGet(() -> Script.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .createdAt(now)
                .build());
        script.setLockedIdeaId(project.getLockedIdeaId());
        script.setStatus(DraftStatus.DRAFT);
        script.setScriptText(parsed.scriptText());
        script.setPacingStyle(parsed.pacingStyle());
        script.setEmotionalArc(parsed.emotionalArc());
        script.setHookStrategy(parsed.hookStrategy());
        script.setNoHumans(Boolean.TRUE.equals(parsed.noHumans()));
        script.setLogline(parsed.logline());
        script.setCentralConflict(parsed.centralConflict());
        script.setEndingPayoff(parsed.endingPayoff());
        script.setSetting(parsed.setting());
        script.setHook(parsed.hook());
        script.setBeatPlan(beatPlan.isBlank() ? null : beatPlan);
        script.setStorytellingType(parsed.storytellingType());
        script.setUpdatedAt(now);
        script = scriptRepository.save(script);
        UUID scriptId = script.getId();

        // CRITIC, not GENERATED, when the accepted draft only exists because an earlier attempt
        // this same call got rejected by critiqueScript() above and had to be revised -- lets
        // version history show "the critic made it rewrite this" (and why, via critiqueNotes)
        // instead of treating every automated attempt the same as a plain one-shot generation.
        snapshotVersion(tenantId, projectId, script,
                critiqueNotes == null ? GenerationSource.GENERATED : GenerationSource.CRITIC, null, critiqueNotes, now);

        List<ScriptCharacter> characters = (parsed.characters() == null ? List.<ScriptGenerationResult.CharacterItem>of() : parsed.characters())
                .stream()
                .map(item -> upsertCharacter(tenantId, scriptId, item, now))
                .collect(Collectors.toList());

        autoAssignSingleProduct(tenantId, projectId, characters, productProfiles);
        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SCRIPT_READY);

        return toView(script, characters);
    }

    /** An explicit per-call duration wins and becomes the project's remembered default (via
     * {@link com.dalai.llama.preprod.domain.entity.ProjectConfig#getTargetDurationSeconds()}) the
     * first time one is set -- future stages (shot-list, video generation) then have a real value
     * to plan around without the creator having to specify it again. */
    private int resolveDuration(UUID tenantId, UUID projectId, Integer requestedDuration) {
        if (requestedDuration != null) {
            var config = projectConfigService.getEntityOrDefault(projectId);
            if (config != null && config.getTargetDurationSeconds() == null) {
                projectConfigService.update(tenantId, projectId,
                        new com.dalai.llama.preprod.dto.UpdateProjectConfigRequest(null, requestedDuration, null, null, null, null, null, null, null, null));
            }
            return requestedDuration;
        }
        var config = projectConfigService.getEntityOrDefault(projectId);
        return config != null && config.getTargetDurationSeconds() != null ? config.getTargetDurationSeconds() : 60;
    }

    /** Reads the project's saved dialogue-language choice (set via the project-settings panel,
     * same "set once, every stage reads it" pattern as {@link #resolveDuration}) -- falls back to
     * English rather than failing generation when the creator hasn't picked one yet. */
    private String resolveDialogueLanguage(UUID projectId) {
        var config = projectConfigService.getEntityOrDefault(projectId);
        String language = config == null ? null : config.getDialogueLanguage();
        return (language == null || language.isBlank()) ? "en-US" : language;
    }

    /** Only PRODUCT-typed profiles are valid grounding here -- an ACTOR profile has no place in
     * "what products does this ad feature". */
    private List<CastProfile> loadProductProfiles(UUID tenantId, List<UUID> productCastProfileIds) {
        if (productCastProfileIds == null || productCastProfileIds.isEmpty()) {
            return List.of();
        }
        return productCastProfileIds.stream()
                .map(id -> castProfileService.requireCastProfile(tenantId, id))
                .peek(profile -> {
                    if (profile.getProfileType() != CastProfileType.PRODUCT) {
                        throw PreProductionException.badRequest(
                                "Cast profile " + profile.getId() + " is not a PRODUCT profile");
                    }
                })
                .collect(Collectors.toList());
    }

    private String productContextBlock(List<CastProfile> productProfiles) {
        if (productProfiles.isEmpty()) {
            return "";
        }
        String lines = productProfiles.stream()
                .map(p -> "- " + p.getDisplayName() + (p.getDescription() == null || p.getDescription().isBlank() ? "" : ": " + p.getDescription()))
                .collect(Collectors.joining("\n"));
        return "Real products this ad must feature (write product-hero shots around these, do not invent a different product):\n" + lines;
    }

    /** Only auto-links when there's exactly one product to place and exactly one PRODUCT-typed
     * character came back -- anything more ambiguous (which product goes with which character) is
     * left for the creator to assign explicitly in the Cast tab, same as every human character
     * already requires. */
    private void autoAssignSingleProduct(UUID tenantId, UUID projectId, List<ScriptCharacter> characters, List<CastProfile> productProfiles) {
        if (productProfiles.size() != 1) {
            return;
        }
        List<ScriptCharacter> productCharacters = characters.stream()
                .filter(c -> c.getCharacterType() == CharacterType.PRODUCT)
                .collect(Collectors.toList());
        if (productCharacters.size() != 1) {
            return;
        }
        castAssignmentService.assign(tenantId, projectId,
                new CreateCastAssignmentRequest(productCharacters.get(0).getId(), productProfiles.get(0).getId(), null, null));
    }

    /** Powers a change request's "apply" -- reuses the exact same generate() flow with the
     * existing script text plus the requested change folded into the brief, rather than a
     * separate LLM call/prompt path. */
    @Transactional
    public ScriptView regenerateWithNote(UUID tenantId, UUID projectId, String note) {
        Script existing = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet to revise"));
        String briefText = existing.getScriptText() + "\n\nRequested change: " + note;
        return generate(tenantId, projectId, new GenerateScriptRequest(briefText, null, null));
    }

    /** Manual correction/refinement of one character -- only fields present in the request change.
     * Does not touch generation: the next regenerate still overwrites this character from scratch,
     * same as every other stage's "editing is a stopgap, not a fork" convention in this service. */
    @Transactional
    public ScriptCharacterView updateCharacter(UUID tenantId, UUID projectId, UUID characterId, UpdateScriptCharacterRequest request) {
        Script script = scriptRepository.findByProjectId(projectId)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No script for project " + projectId));
        ScriptCharacter character = scriptCharacterRepository.findById(characterId)
                .filter(c -> c.getScriptId().equals(script.getId()))
                .orElseThrow(() -> PreProductionException.notFound("No character " + characterId + " on this project's script"));

        if (request.characterName() != null) character.setCharacterName(request.characterName());
        if (request.characterRole() != null) character.setCharacterRole(request.characterRole());
        if (request.description() != null) character.setDescription(request.description());
        if (request.characterType() != null) character.setCharacterType(TolerantEnumParser.parse(CharacterType.class, request.characterType(), character.getCharacterType()));
        if (request.gender() != null) character.setGender(request.gender());
        if (request.age() != null) character.setAge(request.age());
        if (request.ageRange() != null) character.setAgeRange(request.ageRange());
        if (request.look() != null) character.setLook(request.look());
        if (request.complexion() != null) character.setComplexion(request.complexion());
        if (request.profile() != null) character.setProfile(request.profile());
        if (request.persona() != null) character.setPersona(request.persona());
        if (request.backstory() != null) character.setBackstory(request.backstory());
        if (request.motivation() != null) character.setMotivation(request.motivation());
        if (request.fearOrBlock() != null) character.setFearOrBlock(request.fearOrBlock());
        if (request.relationshipToStory() != null) character.setRelationshipToStory(request.relationshipToStory());
        if (request.speakingStyle() != null) character.setSpeakingStyle(request.speakingStyle());
        if (request.visualIdentity() != null) character.setVisualIdentity(request.visualIdentity());

        character = scriptCharacterRepository.save(character);
        return new ScriptCharacterView(character.getId(), character.getCharacterKey(), character.getCharacterName(), character.getCharacterRole(),
                character.getDescription(), character.getCharacterType(), character.getGender(), character.getAge(), character.getAgeRange(),
                character.getLook(), character.getComplexion(), character.getProfile(), character.getPersona(), character.getBackstory(),
                character.getMotivation(), character.getFearOrBlock(), character.getRelationshipToStory(), character.getSpeakingStyle(),
                character.getVisualIdentity());
    }

    /** REQUIRES_NEW -- callers that treat a missing script as non-fatal (e.g. {@code
     * PublicProjectService#view}'s {@code tolerantly}, {@code ProjectLockService#ingestScript})
     * catch the not-found exception this throws, but Spring marks the AMBIENT transaction
     * rollback-only at the point the exception crosses this method's proxy boundary regardless of
     * whether the caller catches it -- surfacing later as an unrelated {@code
     * UnexpectedRollbackException} when that ambient transaction tries to commit (confirmed live:
     * this broke the public review page for any project with no script yet). A dedicated
     * transaction means a "not found" here can only ever affect this one read. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ScriptView get(UUID tenantId, UUID projectId) {
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.notFound("No script for project " + projectId));
        return toView(script, scriptCharacterRepository.findByScriptId(script.getId()));
    }

    /** Saves a creator's manual edit as a new EDITED version -- no LLM call, a direct write. Also
     * updates the live {@code Script} row (what every other stage in this codebase reads) to
     * match, so the edit actually takes effect rather than just existing in history. Fields the
     * request omits fall back to {@code parentVersion}'s values, not the live row's -- the edit is
     * defined relative to the version the creator was looking at, even if generate() has moved the
     * live row on since (an edit started from an older version is still a coherent snapshot). */
    @Transactional
    public ScriptView saveEdit(UUID tenantId, UUID projectId, Integer parentVersion, SaveScriptEditRequest request) {
        ScriptVersion parent = scriptVersionRepository.findByProjectIdAndVersion(projectId, parentVersion)
                .filter(v -> v.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No script version " + parentVersion + " for project " + projectId));
        Script script = scriptRepository.findByProjectId(projectId)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No script for project " + projectId));

        OffsetDateTime now = OffsetDateTime.now();
        script.setScriptText(request.scriptText());
        script.setPacingStyle(request.pacingStyle() != null ? request.pacingStyle() : parent.getPacingStyle());
        script.setEmotionalArc(request.emotionalArc() != null ? request.emotionalArc() : parent.getEmotionalArc());
        script.setHookStrategy(request.hookStrategy() != null ? request.hookStrategy() : parent.getHookStrategy());
        script.setNoHumans(request.noHumans() != null ? request.noHumans() : parent.getNoHumans());
        script.setLogline(request.logline() != null ? request.logline() : parent.getLogline());
        script.setCentralConflict(request.centralConflict() != null ? request.centralConflict() : parent.getCentralConflict());
        script.setEndingPayoff(request.endingPayoff() != null ? request.endingPayoff() : parent.getEndingPayoff());
        script.setSetting(request.setting() != null ? request.setting() : parent.getSetting());
        script.setHook(request.hook() != null ? request.hook() : parent.getHook());
        script.setStorytellingType(request.storytellingType() != null ? request.storytellingType() : parent.getStorytellingType());
        script.setUpdatedAt(now);
        script = scriptRepository.save(script);

        snapshotVersion(tenantId, projectId, script, GenerationSource.EDITED, parent.getId(), null, now);

        return toView(script, scriptCharacterRepository.findByScriptId(script.getId()));
    }

    /** One specific version's content -- what "previous version" / "next version" navigation
     * reads. Characters aren't included (they're not versioned, see {@link ScriptVersion}'s
     * javadoc) -- a caller wanting the live character list uses {@link #get} instead. */
    @Transactional(readOnly = true)
    public ScriptVersionView getVersion(UUID tenantId, UUID projectId, Integer version) {
        ScriptVersion v = scriptVersionRepository.findByProjectIdAndVersion(projectId, version)
                .filter(sv -> sv.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No script version " + version + " for project " + projectId));
        return toVersionView(v);
    }

    /** Every version ever saved for this project, oldest first -- populates a version picker. */
    @Transactional(readOnly = true)
    public List<ScriptVersionView> listVersions(UUID tenantId, UUID projectId) {
        return scriptVersionRepository.findByProjectIdOrderByVersionAsc(projectId).stream()
                .filter(v -> v.getTenantId().equals(tenantId))
                .map(this::toVersionView)
                .collect(Collectors.toList());
    }

    private void snapshotVersion(UUID tenantId, UUID projectId, Script script, GenerationSource source, UUID parentId, String critiqueNotes, OffsetDateTime now) {
        int nextVersion = scriptVersionRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(v -> v.getVersion() + 1)
                .orElse(1);
        scriptVersionRepository.save(ScriptVersion.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .scriptId(script.getId())
                .version(nextVersion)
                .source(source)
                .parentId(parentId)
                .scriptText(script.getScriptText())
                .pacingStyle(script.getPacingStyle())
                .emotionalArc(script.getEmotionalArc())
                .hookStrategy(script.getHookStrategy())
                .noHumans(script.getNoHumans())
                .logline(script.getLogline())
                .centralConflict(script.getCentralConflict())
                .endingPayoff(script.getEndingPayoff())
                .setting(script.getSetting())
                .hook(script.getHook())
                .storytellingType(script.getStorytellingType())
                .critiqueNotes(critiqueNotes)
                .createdAt(now)
                .build());
    }

    private ScriptVersionView toVersionView(ScriptVersion v) {
        return new ScriptVersionView(v.getId(), v.getProjectId(), v.getScriptId(), v.getVersion(), v.getSource(), v.getParentId(),
                v.getScriptText(), v.getPacingStyle(), v.getEmotionalArc(), v.getHookStrategy(), v.getNoHumans(),
                v.getLogline(), v.getCentralConflict(), v.getEndingPayoff(), v.getSetting(), v.getHook(), v.getStorytellingType(),
                v.getCritiqueNotes(), v.getCreatedAt());
    }

    private ScriptCharacter upsertCharacter(UUID tenantId, UUID scriptId, ScriptGenerationResult.CharacterItem item, OffsetDateTime now) {
        ScriptCharacter character = scriptCharacterRepository.findByScriptIdAndCharacterKey(scriptId, item.characterKey())
                .orElseGet(() -> ScriptCharacter.builder()
                        .tenantId(tenantId)
                        .scriptId(scriptId)
                        .characterKey(item.characterKey())
                        .createdAt(now)
                        .build());
        character.setCharacterName(item.characterName());
        character.setCharacterRole(item.characterRole());
        character.setDescription(item.description());
        character.setCharacterType(TolerantEnumParser.parse(CharacterType.class, item.characterType(), CharacterType.HUMAN));
        character.setGender(item.gender());
        character.setAge(item.age());
        character.setAgeRange(item.ageRange());
        character.setLook(item.look());
        character.setComplexion(item.complexion());
        character.setProfile(item.profile());
        character.setPersona(item.persona());
        character.setBackstory(item.backstory());
        character.setMotivation(item.motivation());
        character.setFearOrBlock(item.fearOrBlock());
        character.setRelationshipToStory(item.relationshipToStory());
        character.setSpeakingStyle(item.speakingStyle());
        character.setVisualIdentity(item.visualIdentity());
        return scriptCharacterRepository.save(character);
    }

    /** One call, no retry -- restores creator-service's real HOOK_BEAT_PLAN_GENERATE stage
     * (structure planned before prose), folded into the brief text rather than a separate
     * persisted entity or an extra PRE_PROD_SCRIPT_GENERATE prompt variable. Returns "" (not an
     * exception) on any failure -- a missing beat plan degrades to "no structural pre-plan", it
     * must never block script generation entirely. */
    private String generateHookBeatPlan(UUID tenantId, UUID projectId, String briefText, int durationSeconds) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "hook-beat-plan-" + projectId,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, HOOK_BEAT_PLAN_TASK_KEY,
                            Map.of("brief", briefText, "durationSeconds", String.valueOf(durationSeconds))));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return "";
            }
            HookBeatPlanResult plan = objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), HookBeatPlanResult.class);
            if (plan.beats() == null || plan.beats().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (plan.hookLine() != null && !plan.hookLine().isBlank()) {
                sb.append("Hook: ").append(plan.hookLine()).append("\n");
            }
            for (int i = 0; i < plan.beats().size(); i++) {
                HookBeatPlanResult.BeatItem beat = plan.beats().get(i);
                sb.append(i + 1).append(". ").append(beat.title()).append(" -- ").append(beat.purpose());
                if (beat.emotionalTarget() != null && !beat.emotionalTarget().isBlank()) {
                    sb.append(" (emotional target: ").append(beat.emotionalTarget()).append(")");
                }
                if (beat.payoff() != null && !beat.payoff().isBlank()) {
                    sb.append(" [payoff: ").append(beat.payoff()).append("]");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** One critique call per attempt -- restores creator-service's real SCRIPT_CRITIC pattern.
     * Never blocks generation on a parse failure: an unparseable critique is treated as a pass
     * (no feedback to act on), not a hard failure of the whole generate() call. */
    private ScriptCritiqueResult critiqueScript(UUID tenantId, UUID projectId, String briefText, ScriptGenerationResult parsed) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "script-critic-" + projectId,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, CRITIC_TASK_KEY,
                            Map.of(
                                    "brief", briefText,
                                    "scriptText", parsed.scriptText(),
                                    "logline", parsed.logline() == null ? "" : parsed.logline(),
                                    "centralConflict", parsed.centralConflict() == null ? "" : parsed.centralConflict(),
                                    "hook", parsed.hook() == null ? "" : parsed.hook()
                            )));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return new ScriptCritiqueResult("PASS", null, null, null, null, List.of());
            }
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ScriptCritiqueResult.class);
        } catch (Exception ex) {
            return new ScriptCritiqueResult("PASS", null, null, null, null, List.of());
        }
    }

    private ScriptGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for PRE_PROD_SCRIPT_GENERATE");
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ScriptGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SCRIPT_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ScriptView toView(Script script, List<ScriptCharacter> characters) {
        List<ScriptCharacterView> characterViews = characters.stream()
                .map(c -> new ScriptCharacterView(c.getId(), c.getCharacterKey(), c.getCharacterName(), c.getCharacterRole(), c.getDescription(),
                        c.getCharacterType(), c.getGender(), c.getAge(), c.getAgeRange(), c.getLook(), c.getComplexion(), c.getProfile(), c.getPersona(),
                        c.getBackstory(), c.getMotivation(), c.getFearOrBlock(), c.getRelationshipToStory(), c.getSpeakingStyle(), c.getVisualIdentity()))
                .collect(Collectors.toList());
        ScriptVersion latest = scriptVersionRepository.findTopByProjectIdOrderByVersionDesc(script.getProjectId()).orElse(null);
        return new ScriptView(script.getId(), script.getProjectId(), script.getLockedIdeaId(), script.getStatus(), script.getScriptText(),
                script.getPacingStyle(), script.getEmotionalArc(), script.getHookStrategy(), script.getNoHumans(),
                script.getLogline(), script.getCentralConflict(), script.getEndingPayoff(), script.getSetting(), script.getHook(), script.getBeatPlan(),
                script.getStorytellingType(), characterViews, latest == null ? null : latest.getVersion(), latest == null ? null : latest.getSource());
    }
}
