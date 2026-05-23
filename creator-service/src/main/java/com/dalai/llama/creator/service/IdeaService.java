package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorCharacterCastMapping;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorProfile;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.dto.request.GenerateStoryIdeaScriptRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryScriptRequest;
import com.dalai.llama.creator.dto.request.SaveGeneratedScriptRequest;
import com.dalai.llama.creator.dto.request.SaveStoryScriptRequest;
import com.dalai.llama.creator.dto.response.GeneratedIdeaResponse;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.dto.response.ShotProductionPlanTagResponse;
import com.dalai.llama.creator.exception.CreatorAiOutputException;
import com.dalai.llama.creator.repository.CreatorCharacterCastMappingRepository;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorProfileRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IdeaService {

    private static final Logger log = LoggerFactory.getLogger(IdeaService.class);

    private static final int GENERATED_IDEA_COUNT = 20;
    private static final int GENERATED_IDEA_AI_BATCH_SIZE = 5;
    private static final int DEFAULT_PAGE_SIZE = 5;

    private static final List<String> IDEA_ANGLES = List.of(
            "Contrarian opener",
            "Beginner mistake",
            "Before-after reveal",
            "Silent reaction",
            "Myth versus reality",
            "One small win",
            "Family or friend interruption",
            "Countdown challenge",
            "Confession hook",
            "Unexpected comparison",
            "Saved checklist",
            "Day one diary",
            "POV comedy beat",
            "Quick transformation",
            "Behind-the-scenes truth",
            "Two character conflict",
            "Mini tutorial",
            "Relatable failure",
            "Emotional payoff",
            "Shareable punchline"
    );

    private final CreatorIdeaRepository ideaRepository;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final CreatorCharacterCastMappingRepository characterCastMappingRepository;
    private final CreatorProfileRepository profileRepository;
    private final PromptTemplateService promptTemplateService;
    private final CreatorAiService creatorAiService;
    private final GenerationJobService generationJobService;
    private final ScriptStructureService scriptStructureService;
    private final ProductionPlanTagService productionPlanTagService;
    private final CreatorProjectService projectService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdeaService(
            CreatorIdeaRepository ideaRepository,
            CreatorScriptRepository scriptRepository,
            CreatorPromptRunRepository promptRunRepository,
            CreatorCharacterCastMappingRepository characterCastMappingRepository,
            CreatorProfileRepository profileRepository,
            PromptTemplateService promptTemplateService,
            CreatorAiService creatorAiService,
            GenerationJobService generationJobService,
            ScriptStructureService scriptStructureService,
            ProductionPlanTagService productionPlanTagService,
            CreatorProjectService projectService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.ideaRepository = ideaRepository;
        this.scriptRepository = scriptRepository;
        this.promptRunRepository = promptRunRepository;
        this.characterCastMappingRepository = characterCastMappingRepository;
        this.profileRepository = profileRepository;
        this.promptTemplateService = promptTemplateService;
        this.creatorAiService = creatorAiService;
        this.generationJobService = generationJobService;
        this.scriptStructureService = scriptStructureService;
        this.productionPlanTagService = productionPlanTagService;
        this.projectService = projectService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Page<GeneratedIdeaResponse> generateIdeasForLockedBrief(
            UUID lockedIdeaId,
            String tenantId,
            String userId,
            Pageable pageable
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = ideaRepository.findById(lockedIdeaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found."));
        if (!safeTenantId.equals(lockedIdea.getTenantId()) || !safeUserId.equals(lockedIdea.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found.");
        }
        if (!"LOCKED".equalsIgnoreCase(lockedIdea.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idea must be locked before generating candidates.");
        }
        lockedIdea = ensureProjectOnLockedIdea(lockedIdea);
        log.info(
                "Creator idea generation started lockedIdeaId={} tenantId={} userId={} source={} title=\"{}\" page={} size={} provider={} model={}",
                lockedIdeaId,
                safeTenantId,
                safeUserId,
                lockedIdea.getSource(),
                lockedIdea.getTitle(),
                pageable == null ? null : pageable.getPageNumber(),
                pageable == null ? null : pageable.getPageSize(),
                creatorAiService.providerName(),
                creatorAiService.modelName()
        );

        Map<String, Object> jobInput = buildIdeaGenerationJobInput(lockedIdea);

        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.IDEA_GENERATE.name(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getProjectId(),
                jobInput
        );
        log.info(
                "Creator idea generation job created jobId={} lockedIdeaId={} tenantId={} userId={}",
                generationJob.getId(),
                lockedIdeaId,
                safeTenantId,
                safeUserId
        );

        try {
            IdeaGenerationResult generationResult = ensureGeneratedIdeas(lockedIdea, generationJob.getId());
            Pageable normalizedPageable = normalizePageable(pageable);
            Page<GeneratedIdeaResponse> response = ideaRepository
                    .findGeneratedIdeasForLockedBrief(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable)
                    .map(this::toResponse);

            Map<String, Object> jobOutput = buildIdeaGenerationJobOutput(lockedIdeaId, generationResult, response);
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);
            log.info(
                    "Creator idea generation completed jobId={} lockedIdeaId={} tenantId={} userId={} generatedCount={} returnedCount={} totalAvailable={} promptRuns={} provider={} model={}",
                    generationJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    generationResult.generatedCount(),
                    response.getNumberOfElements(),
                    response.getTotalElements(),
                    generationResult.promptRunIds().size(),
                    creatorAiService.providerName(),
                    creatorAiService.modelName()
            );

            return response;
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error(
                    "Creator idea generation failed jobId={} lockedIdeaId={} tenantId={} userId={} provider={} model={} errorType={} errorMessage={}",
                    generationJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    creatorAiService.providerName(),
                    creatorAiService.modelName(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
            throw ex;
        }
    }

    @Transactional
    public CreatorGenerationJob startGenerateIdeasForLockedBriefJob(
            UUID lockedIdeaId,
            String tenantId,
            String userId,
            Pageable pageable
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = ideaRepository.findById(lockedIdeaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found."));
        if (!safeTenantId.equals(lockedIdea.getTenantId()) || !safeUserId.equals(lockedIdea.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found.");
        }
        if (!"LOCKED".equalsIgnoreCase(lockedIdea.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idea must be locked before generating candidates.");
        }
        lockedIdea = ensureProjectOnLockedIdea(lockedIdea);

        Map<String, Object> jobInput = buildIdeaGenerationJobInput(lockedIdea);
        jobInput.put("page", pageable == null ? 0 : pageable.getPageNumber());
        jobInput.put("size", pageable == null ? DEFAULT_PAGE_SIZE : pageable.getPageSize());
        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.IDEA_GENERATE.name(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getProjectId(),
                jobInput
        );
        log.info(
                "Creator async idea generation job accepted jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={}",
                generationJob.getId(),
                lockedIdeaId,
                safeTenantId,
                safeUserId,
                pageable == null ? null : pageable.getPageNumber(),
                pageable == null ? null : pageable.getPageSize()
        );
        return generationJob;
    }

    @Transactional
    public void runGenerateIdeasForLockedBriefJob(
            UUID generationJobId,
            UUID lockedIdeaId,
            String tenantId,
            String userId,
            Pageable pageable
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        try {
            CreatorIdea lockedIdea = ideaRepository.findById(lockedIdeaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found."));
            if (!safeTenantId.equals(lockedIdea.getTenantId()) || !safeUserId.equals(lockedIdea.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found.");
            }
            if (!"LOCKED".equalsIgnoreCase(lockedIdea.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idea must be locked before generating candidates.");
            }
            lockedIdea = ensureProjectOnLockedIdea(lockedIdea);

            generationJobService.updateGenerationJobProgress(generationJobId, 15, "Generating story ideas with AI");
            IdeaGenerationResult generationResult = ensureGeneratedIdeas(lockedIdea, generationJobId);
            generationJobService.updateGenerationJobProgress(generationJobId, 82, "Preparing generated story ideas");

            Pageable normalizedPageable = normalizePageable(pageable);
            Page<GeneratedIdeaResponse> response = ideaRepository
                    .findGeneratedIdeasForLockedBrief(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable)
                    .map(this::toResponse);

            Map<String, Object> jobOutput = buildIdeaGenerationJobOutput(lockedIdeaId, generationResult, response);
            jobOutput.put("message", "Story ideas generated");
            generationJobService.completeGenerationJob(generationJobId, jobOutput);
            log.info(
                    "Creator async idea generation completed jobId={} lockedIdeaId={} tenantId={} userId={} generatedCount={} returnedCount={} totalAvailable={}",
                    generationJobId,
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    generationResult.generatedCount(),
                    response.getNumberOfElements(),
                    response.getTotalElements()
            );
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error(
                    "Creator async idea generation failed jobId={} lockedIdeaId={} tenantId={} userId={} errorType={} errorMessage={}",
                    generationJobId,
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
            throw ex;
        }
    }

    private Map<String, Object> buildIdeaGenerationJobInput(CreatorIdea lockedIdea) {
        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("lockedIdeaId", lockedIdea.getId().toString());
        if (lockedIdea.getProjectId() != null) {
            jobInput.put("projectId", lockedIdea.getProjectId().toString());
        }
        jobInput.put("lockedIdeaTitle", lockedIdea.getTitle());
        jobInput.put("durationSeconds", lockedIdea.getDurationSeconds());
        jobInput.put("source", lockedIdea.getSource());
        jobInput.put("selectionContext", lockedIdea.getSelectionContext());
        return jobInput;
    }

    private Map<String, Object> buildIdeaGenerationJobOutput(
            UUID lockedIdeaId,
            IdeaGenerationResult generationResult,
            Page<GeneratedIdeaResponse> response
    ) {
        Map<String, Object> jobOutput = new LinkedHashMap<>();
        jobOutput.put("lockedIdeaId", lockedIdeaId.toString());
        jobOutput.put("generatedCount", generationResult.generatedCount());
        jobOutput.put("returnedCount", response.getNumberOfElements());
        jobOutput.put("totalAvailable", response.getTotalElements());
        jobOutput.put("page", generatedIdeaPagePayload(response));
        if (!generationResult.promptRunIds().isEmpty()) {
            jobOutput.put("promptRunIds", generationResult.promptRunIds().stream()
                    .map(UUID::toString)
                    .toList());
            jobOutput.put("provider", creatorAiService.providerName());
            jobOutput.put("model", creatorAiService.modelName());
            jobOutput.put("aiOutputs", generationResult.providerOutputs());
        }
        return jobOutput;
    }

    private Map<String, Object> generatedIdeaPagePayload(Page<GeneratedIdeaResponse> response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", response.getContent().stream()
                .map(this::generatedIdeaPayload)
                .toList());
        payload.put("number", response.getNumber());
        payload.put("size", response.getSize());
        payload.put("totalElements", response.getTotalElements());
        payload.put("totalPages", response.getTotalPages());
        payload.put("numberOfElements", response.getNumberOfElements());
        payload.put("first", response.isFirst());
        payload.put("last", response.isLast());
        payload.put("empty", response.isEmpty());
        return payload;
    }

    private Map<String, Object> generatedIdeaPayload(GeneratedIdeaResponse idea) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", idea.id() == null ? null : idea.id().toString());
        payload.put("lockedIdeaId", idea.lockedIdeaId() == null ? null : idea.lockedIdeaId().toString());
        payload.put("title", idea.title());
        payload.put("description", idea.description());
        payload.put("source", idea.source());
        payload.put("durationSeconds", idea.durationSeconds());
        payload.put("hashtags", idea.hashtags());
        payload.put("creativeNotes", idea.creativeNotes());
        payload.put("createdAt", idea.createdAt() == null ? null : idea.createdAt().toString());
        return payload;
    }

    @Transactional
    public GeneratedIdeaResponse saveStoryIdea(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            String tenantId,
            String userId
    ) {
        CreatorIdea storyIdea = getStoryIdeaForLockedBrief(lockedIdeaId, storyIdeaId, tenantId, userId);
        storyIdea.setSaved(true);
        storyIdea.setStatus("SELECTED");
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        CreatorIdea savedIdea = ideaRepository.save(storyIdea);
        linkProjectSelectedIdea(savedIdea);
        return toResponse(savedIdea);
    }

    @Transactional
    public GeneratedStoryScriptResponse generateStoryScriptForStoryIdea(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryScriptRequest request,
            String tenantId,
            String userId
    ) {
        CreatorIdea storyIdea = getStoryIdeaForLockedBrief(lockedIdeaId, storyIdeaId, tenantId, userId);
        if (!storyIdea.isSaved() && !"SELECTED".equalsIgnoreCase(storyIdea.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save the story idea before generating script.");
        }

        String ideaText = defaultString(request == null ? null : request.idea(), storyIdea.getTitle() + "\n" + defaultString(storyIdea.getSummary(), ""));
        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds());
        String categoryCode = resolveStoryScriptCategory(request == null ? null : request.categoryCode(), storyIdea, ideaText);
        String dialogueLanguage = normalizeDialogueLanguage(request == null ? null : request.dialogueLanguage());
        String screenType = normalizeScreenType(request == null ? null : request.screenType());
        String inferredTone = inferTone(ideaText, categoryCode);

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.STORY_SCRIPT_GENERATE.name());
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("duration", durationSeconds);
        inputSnapshot.put("idea", ideaText);
        inputSnapshot.put("category", categoryCode);
        inputSnapshot.put("dialogueLanguage", dialogueLanguage);
        inputSnapshot.put("screenType", screenType);
        inputSnapshot.put("tone", inferredTone);
        inputSnapshot.put("lockedIdeaId", lockedIdeaId);
        inputSnapshot.put("storyIdeaId", storyIdeaId);
        inputSnapshot.put("storyIdea", toPromptIdeaMap(storyIdea));
        inputSnapshot.put("context", request == null || request.context() == null ? Map.of() : request.context());

        String renderedPrompt = promptTemplateService.render(template, inputSnapshot);
        Map<String, Object> providerInput = new LinkedHashMap<>(inputSnapshot);
        providerInput.put("renderedPrompt", renderedPrompt);
        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.STORY_SCRIPT_GENERATE.name(),
                storyIdea.getTenantId(),
                storyIdea.getUserId(),
                storyIdea.getProjectId(),
                providerInput
        );

        Map<String, Object> providerOutputForDebug = new LinkedHashMap<>();
        Map<String, Object> aiOutputDiagnostics = new LinkedHashMap<>();
        try {
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    storyIdea.getTenantId(),
                    storyIdea.getUserId(),
                    storyIdea.getProjectId(),
                    generationJob.getId(),
                    null
            );
            CreatorAiService.MeteredAiResponse aiResponse =
                    creatorAiService.generateMetered(PromptTemplateType.STORY_SCRIPT_GENERATE.name(), providerInput, usageContext);
            Map<String, Object> providerOutput = aiResponse.output();
            providerOutputForDebug = copyDebugMap(providerOutput);

            GeneratedStoryScriptResponse.StoryScript storyScript = resolveStoryScriptPayload(
                    providerOutput,
                    storyIdea,
                    durationSeconds,
                    categoryCode,
                    inferredTone,
                    dialogueLanguage,
                    screenType,
                    aiOutputDiagnostics
            );
            Map<String, Object> storyScriptMap = toStoryScriptMap(storyScript);
            Map<String, Object> promptOutputPayload = new LinkedHashMap<>(storyScriptMap);
            promptOutputPayload.put("providerOutput", providerOutput);
            promptOutputPayload.put("aiOutputDiagnostics", aiOutputDiagnostics);

            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(storyIdea.getTenantId())
                    .userId(storyIdea.getUserId())
                    .projectId(storyIdea.getProjectId())
                    .jobId(generationJob.getId())
                    .promptTemplateId(template.getId())
                    .promptTemplateKey(template.getTemplateKey())
                    .promptTemplateVersion(template.getVersion())
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(inputSnapshot)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(promptOutputPayload)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(
                    PromptTemplateType.STORY_SCRIPT_GENERATE.name(),
                    aiResponse,
                    usageContext.withPromptRunId(promptRun.getId())
            );
            Map<String, Object> rawPromptResponse = rawPromptResponse(
                    promptRun.getId(),
                    PromptTemplateType.STORY_SCRIPT_GENERATE.name(),
                    providerOutput,
                    aiOutputDiagnostics
            );

            String scriptText = buildStoryScriptText(storyScript);
            CreatorIdea savedIdea = saveStoryScriptOnIdea(storyIdea, storyScript, scriptText, promptRun.getId(), generationJob.getId(), durationSeconds, dialogueLanguage, screenType);
            linkProjectSelectedIdea(savedIdea);

            Map<String, Object> jobOutput = new LinkedHashMap<>(promptOutputPayload);
            jobOutput.put("rawPromptResponse", rawPromptResponse);
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("storyIdeaId", savedIdea.getId().toString());
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return toGeneratedStoryScriptResponse(savedIdea, lockedIdeaId, promptRun.getId(), storyScript, scriptText, rawPromptResponse);
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(
                    generationJob.getId(),
                    defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                    rawPromptFailureOutput(ex, PromptTemplateType.STORY_SCRIPT_GENERATE.name(), providerOutputForDebug, aiOutputDiagnostics)
            );
            throw ex;
        }
    }

    @Transactional
    public GeneratedStoryScriptResponse saveStoryScript(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            SaveStoryScriptRequest request,
            String tenantId,
            String userId
    ) {
        CreatorIdea storyIdea = getStoryIdeaForLockedBrief(lockedIdeaId, storyIdeaId, tenantId, userId);
        GeneratedStoryScriptResponse.StoryScript storyScript = request == null ? null : request.scriptJson();
        if (storyScript == null) {
            storyScript = readStoryScriptFromIdea(storyIdea);
        }
        if (storyScript == null) {
            storyScript = buildStoryScriptPayload(
                    storyIdea,
                    normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds()),
                    inferCategory(storyIdea),
                    inferTone(storyIdea.getTitle() + " " + defaultString(storyIdea.getSummary(), ""), inferCategory(storyIdea)),
                    normalizeDialogueLanguage(request == null ? null : request.dialogueLanguage()),
                    normalizeScreenType(request == null ? null : request.screenType())
            );
        }

        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyScript.getDuration());
        String dialogueLanguage = normalizeDialogueLanguage(defaultString(request == null ? null : request.dialogueLanguage(), storyScript.getDialogueLanguage()));
        String screenType = normalizeScreenType(defaultString(request == null ? null : request.screenType(), storyScript.getScreenType()));
        String title = defaultString(request == null ? null : request.title(), defaultString(storyScript.getProjectTitle(), storyIdea.getTitle()));
        storyScript.setProjectTitle(title);
        storyScript.setDuration(durationSeconds);
        storyScript.setDialogueLanguage(dialogueLanguage);
        storyScript.setScreenType(screenType);

        String scriptText = defaultString(request == null ? null : request.scriptText(), buildStoryScriptText(storyScript));
        UUID promptRunId = storyIdea.getPromptRunId();
        CreatorIdea savedIdea = saveStoryScriptOnIdea(storyIdea, storyScript, scriptText, promptRunId, storyIdea.getGenerationJobId(), durationSeconds, dialogueLanguage, screenType);
        linkProjectSelectedIdea(savedIdea);

        return toGeneratedStoryScriptResponse(savedIdea, lockedIdeaId, promptRunId, storyScript, scriptText, null);
    }

    @Transactional
    public GeneratedScriptResponse generateScriptForStoryIdea(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryIdeaScriptRequest request,
            String tenantId,
            String userId
    ) {
        CreatorIdea storyIdea = getStoryIdeaForLockedBrief(lockedIdeaId, storyIdeaId, tenantId, userId);
        if (!storyIdea.isSaved() && !"SELECTED".equalsIgnoreCase(storyIdea.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save the story idea before generating script.");
        }

        String ideaText = defaultString(request == null ? null : request.idea(), storyIdea.getTitle() + "\n" + defaultString(storyIdea.getSummary(), ""));
        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds());
        String dialogueLanguage = normalizeDialogueLanguage(request == null ? null : request.dialogueLanguage());
        String screenType = normalizeScreenType(request == null ? null : request.screenType());
        GeneratedStoryScriptResponse.StoryScript storyScript = readStoryScriptFromIdea(storyIdea);
        if (storyScript == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate and save the story script before creating the shot-wise screenplay.");
        }
        String categoryCode = resolveScreenplayCategory(request == null ? null : request.categoryCode(), storyIdea, storyScript, ideaText);
        String inferredTone = inferTone(ideaText, categoryCode);
        Map<String, Object> requestContext = request == null ? Map.of() : toGenericMap(request.context());
        Map<String, Object> lockedPackageContext = mapValue(requestContext.get("lockedPackage"));
        String budgetTier = defaultString(
                request == null ? null : request.budgetTier(),
                stringValue(requestContext.get("budgetTier"), stringValue(lockedPackageContext.get("budgetTier"), inferBudgetTier(durationSeconds)))
        );
        List<Map<String, Object>> storyCharacters = toGenericMapList(storyScript.getCharacters());
        List<Map<String, Object>> storyBeats = toGenericMapList(storyScript.getBeats());
        List<Map<String, Object>> characterCastMappings = nonEmptyList(
                request == null ? null : toGenericMapList(request.characterCastMappings()),
                nonEmptyList(
                        mapListValue(requestContext.get("characterCastMappings")),
                        nonEmptyList(mapListValue(lockedPackageContext.get("characterCastMappings")), promptCharacterCastMappings(storyIdea, lockedIdeaId, storyIdeaId))
                )
        );
        List<Map<String, Object>> availableActors = nonEmptyList(
                request == null ? null : toGenericMapList(request.availableActors()),
                nonEmptyList(
                        mapListValue(requestContext.get("availableActors")),
                        nonEmptyList(mapListValue(lockedPackageContext.get("availableActors")), promptAvailableActors(storyIdea))
                )
        );
        Map<String, Object> audienceDecision = nonEmptyMap(
                request == null ? null : toGenericMap(request.audienceDecision()),
                nonEmptyMap(mapValue(requestContext.get("audienceDecision")), mapValue(lockedPackageContext.get("audienceDecision")))
        );
        Map<String, Object> brandContext = nonEmptyMap(
                request == null ? null : toGenericMap(request.brandContext()),
                nonEmptyMap(mapValue(requestContext.get("brandContext")), mapValue(lockedPackageContext.get("brandContext")))
        );
        Map<String, Object> creatorContext = nonEmptyMap(
                request == null ? null : toGenericMap(request.creatorContext()),
                nonEmptyMap(mapValue(requestContext.get("creatorContext")), mapValue(lockedPackageContext.get("creatorContext")))
        );

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.SCRIPT_GENERATE.name());
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("duration", durationSeconds);
        inputSnapshot.put("idea", ideaText);
        inputSnapshot.put("category", categoryCode);
        inputSnapshot.put("dialogueLanguage", dialogueLanguage);
        inputSnapshot.put("screenType", screenType);
        inputSnapshot.put("tone", inferredTone);
        inputSnapshot.put("budgetTier", budgetTier);
        inputSnapshot.put("lockedIdeaId", lockedIdeaId);
        inputSnapshot.put("storyIdeaId", storyIdeaId);
        inputSnapshot.put("storyIdea", toPromptIdeaMap(storyIdea));
        inputSnapshot.put("storyScript", storyScript);
        inputSnapshot.put("storyBeats", storyBeats);
        inputSnapshot.put("storyCharacters", storyCharacters);
        inputSnapshot.put("characterCastMappings", characterCastMappings);
        inputSnapshot.put("availableActors", availableActors);
        inputSnapshot.put("audienceDecision", audienceDecision);
        inputSnapshot.put("brandContext", brandContext);
        inputSnapshot.put("creatorContext", creatorContext);
        inputSnapshot.put("context", requestContext);

        String renderedPrompt = promptTemplateService.render(template, inputSnapshot);
        Map<String, Object> providerInput = new LinkedHashMap<>(inputSnapshot);
        providerInput.put("renderedPrompt", renderedPrompt);
        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.SCRIPT_GENERATE.name(),
                storyIdea.getTenantId(),
                storyIdea.getUserId(),
                storyIdea.getProjectId(),
                providerInput
        );

        Map<String, Object> providerOutputForDebug = new LinkedHashMap<>();
        Map<String, Object> aiOutputDiagnostics = new LinkedHashMap<>();
        try {
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    storyIdea.getTenantId(),
                    storyIdea.getUserId(),
                    storyIdea.getProjectId(),
                    generationJob.getId(),
                    null
            );
            CreatorAiService.MeteredAiResponse aiResponse =
                    creatorAiService.generateMetered(PromptTemplateType.SCRIPT_GENERATE.name(), providerInput, usageContext);
            Map<String, Object> providerOutput = aiResponse.output();
            providerOutputForDebug = copyDebugMap(providerOutput);

            GeneratedScriptResponse.CinematicScript scriptPayload;
            try {
                scriptPayload = resolveCinematicScriptPayload(providerOutput, storyIdea, storyScript, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, aiOutputDiagnostics);
            } catch (CreatorAiOutputException ex) {
                if (!shouldRetryScreenplayGeneration(aiOutputDiagnostics)) {
                    throw ex;
                }
                Map<String, Object> firstAttemptDiagnostics = copyDebugMap(aiOutputDiagnostics);
                Map<String, Object> firstAttemptProviderOutput = copyDebugMap(providerOutput);
                Map<String, Object> retryProviderInput = compactScreenplayRetryInput(providerInput, renderedPrompt, firstAttemptDiagnostics, durationSeconds);
                String retryRenderedPrompt = stringValue(retryProviderInput.get("renderedPrompt"), renderedPrompt);
                Map<String, Object> retryDiagnostics = new LinkedHashMap<>();
                CreatorAiService.MeteredAiResponse retryResponse =
                        creatorAiService.generateMetered(PromptTemplateType.SCRIPT_GENERATE.name(), retryProviderInput, usageContext);
                Map<String, Object> retryProviderOutput = retryResponse.output();
                try {
                    scriptPayload = resolveCinematicScriptPayload(retryProviderOutput, storyIdea, storyScript, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, retryDiagnostics);
                } catch (CreatorAiOutputException retryEx) {
                    retryDiagnostics.put("retryAttempt", 1);
                    retryDiagnostics.put("retryOfFailureReason", firstAttemptDiagnostics.getOrDefault("failureReason", ""));
                    retryDiagnostics.put("firstAttemptDiagnostics", firstAttemptDiagnostics);
                    retryDiagnostics.put("firstAttemptProviderPreview", truncate(toJson(firstAttemptProviderOutput), 4000));
                    aiOutputDiagnostics = retryDiagnostics;
                    providerOutputForDebug = copyDebugMap(retryProviderOutput);
                    throw retryEx;
                }
                retryDiagnostics.put("retryAttempt", 1);
                retryDiagnostics.put("retryOfFailureReason", firstAttemptDiagnostics.getOrDefault("failureReason", ""));
                retryDiagnostics.put("firstAttemptDiagnostics", firstAttemptDiagnostics);
                retryDiagnostics.put("firstAttemptProviderPreview", truncate(toJson(firstAttemptProviderOutput), 4000));
                aiResponse = retryResponse;
                providerOutput = retryProviderOutput;
                providerOutputForDebug = copyDebugMap(providerOutput);
                aiOutputDiagnostics = retryDiagnostics;
                providerInput = retryProviderInput;
                renderedPrompt = retryRenderedPrompt;
            }
            scriptPayload.setProvider(creatorAiService.providerName());
            scriptPayload.setModel(creatorAiService.modelName());
            enrichAudioAndMusicDesign(scriptPayload, categoryCode, inferredTone);
            Map<String, Object> scriptPayloadMap = toMap(scriptPayload);
            putStoryStructure(scriptPayloadMap, storyScript);
            putScreenplayPlanningContext(scriptPayloadMap, budgetTier, characterCastMappings, availableActors, audienceDecision, brandContext, creatorContext);
            Map<String, Object> promptOutputPayload = new LinkedHashMap<>(scriptPayloadMap);
            promptOutputPayload.put("providerOutput", providerOutput);
            promptOutputPayload.put("aiOutputDiagnostics", aiOutputDiagnostics);

            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(storyIdea.getTenantId())
                    .userId(storyIdea.getUserId())
                    .projectId(storyIdea.getProjectId())
                    .jobId(generationJob.getId())
                    .promptTemplateId(template.getId())
                    .promptTemplateKey(template.getTemplateKey())
                    .promptTemplateVersion(template.getVersion())
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(inputSnapshot)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(promptOutputPayload)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(
                    PromptTemplateType.SCRIPT_GENERATE.name(),
                    aiResponse,
                    usageContext.withPromptRunId(promptRun.getId())
            );
            Map<String, Object> rawPromptResponse = rawPromptResponse(
                    promptRun.getId(),
                    PromptTemplateType.SCRIPT_GENERATE.name(),
                    providerOutput,
                    aiOutputDiagnostics
            );

            List<GeneratedScriptResponse.CinematicShot> shots = scriptPayload.getShots();
            List<Map<String, Object>> shotPayloads = toMapList(shots);
            String script = buildScriptText(storyIdea, shots);

            CreatorScript creatorScript = scriptRepository.save(CreatorScript.builder()
                    .tenantId(storyIdea.getTenantId())
                    .userId(storyIdea.getUserId())
                    .projectId(storyIdea.getProjectId())
                    .lockedIdeaId(lockedIdeaId)
                    .storyIdeaId(storyIdeaId)
                    .promptRunId(promptRun.getId())
                    .categoryCode(categoryCode)
                    .durationSeconds(durationSeconds)
                    .formatTier(scriptPayload.getFormatTier())
                    .actStructure(scriptPayload.getActStructure())
                    .budgetTier(defaultString(scriptPayload.getBudgetTier(), budgetTier))
                    .totalShots(scriptPayload.getTotalShots())
                    .sceneCount(scriptPayload.getSceneCount())
                    .sequenceCount(scriptPayload.getSequenceCount())
                    .dialogueLanguage(dialogueLanguage)
                    .screenType(screenType)
                    .title(scriptPayload.getProjectTitle())
                    .scriptText(script)
                    .scriptPayload(scriptPayloadMap)
                    .shots(shotPayloads)
                    .status("GENERATED")
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build());
            scriptStructureService.syncFromStoryScript(creatorScript, storyScript);
            ProductionPlanGenerationResult productionPlanResult = productionPlanNotStarted();
            List<ShotProductionPlanTagResponse> productionPlanTags = productionPlanResult.tags();
            enrichScriptPayloadWithProductionPlanTags(scriptPayload, productionPlanTags);
            Map<String, Object> enrichedScriptPayloadMap = toMap(scriptPayload);
            putStoryStructure(enrichedScriptPayloadMap, storyScript);
            putScreenplayPlanningContext(enrichedScriptPayloadMap, budgetTier, characterCastMappings, availableActors, audienceDecision, brandContext, creatorContext);
            putProductionPlanStatus(enrichedScriptPayloadMap, productionPlanResult);
            List<Map<String, Object>> enrichedShotPayloads = toMapList(shots);
            creatorScript.setScriptPayload(enrichedScriptPayloadMap);
            creatorScript.setShots(enrichedShotPayloads);
            creatorScript.setUpdatedAt(OffsetDateTime.now());
            creatorScript = scriptRepository.save(creatorScript);
            scriptStructureService.syncScreenplayShots(creatorScript, enrichedScriptPayloadMap, enrichedShotPayloads);

            Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
            context.put("scriptId", creatorScript.getId().toString());
            context.put("promptRunId", promptRun.getId().toString());
            context.put("generationJobId", generationJob.getId().toString());
            context.put("scriptGeneratedAt", OffsetDateTime.now().toString());
            context.put("scriptSceneCount", shots.size());
            context.put("scriptSource", creatorAiService.providerName());
            context.put("scriptCategoryCode", categoryCode);
            context.put("scriptDurationSeconds", durationSeconds);
            context.put("scriptDialogueLanguage", dialogueLanguage);
            context.put("scriptScreenType", screenType);
            context.put("productionPlanTagCount", productionPlanTags.size());
            context.put("productionPlanStatus", productionPlanResult.status());
            if (!productionPlanResult.error().isBlank()) {
                context.put("productionPlanError", productionPlanResult.error());
            }
            context.put("productionPlanGeneratedAt", OffsetDateTime.now().toString());

            storyIdea.setScript(script);
            storyIdea.setScenes(enrichedShotPayloads);
            storyIdea.setDurationSeconds(durationSeconds);
            storyIdea.setGenerationJobId(generationJob.getId());
            storyIdea.setSelectionContext(context);
            storyIdea.setStatus("SCRIPT_GENERATED");
            storyIdea.setSaved(true);
            storyIdea.setUpdatedAt(OffsetDateTime.now());
            CreatorIdea savedIdea = ideaRepository.save(storyIdea);
            linkProjectSelectedIdea(savedIdea);

            Map<String, Object> jobOutput = new LinkedHashMap<>(promptOutputPayload);
            jobOutput.put("rawPromptResponse", rawPromptResponse);
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("scriptId", creatorScript.getId().toString());
            jobOutput.put("storyIdeaId", savedIdea.getId().toString());
            jobOutput.put("productionPlanTags", productionPlanTagMaps(productionPlanTags));
            jobOutput.put("productionPlanStatus", productionPlanResult.status());
            if (!productionPlanResult.error().isBlank()) {
                jobOutput.put("productionPlanError", productionPlanResult.error());
            }
            if (!productionPlanResult.debug().isEmpty()) {
                jobOutput.put("productionPlanDebug", productionPlanResult.debug());
            }
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return GeneratedScriptResponse.builder()
                    .scriptId(creatorScript.getId())
                    .ideaId(savedIdea.getId())
                    .lockedIdeaId(lockedIdeaId)
                    .projectId(savedIdea.getProjectId())
                    .promptRunId(promptRun.getId())
                    .title(savedIdea.getTitle())
                    .script(savedIdea.getScript())
                    .scriptJson(scriptPayload)
                    .rawPromptResponse(rawPromptResponse)
                    .scenes(shots)
                    .productionPlanTags(productionPlanTags)
                    .productionPlanStatus(productionPlanResult.status())
                    .productionPlanError(productionPlanResult.error())
                    .productionPlanDebug(productionPlanResult.debug())
                    .durationSeconds(savedIdea.getDurationSeconds())
                    .status(savedIdea.getStatus())
                    .generatedAt(savedIdea.getUpdatedAt())
                    .build();
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(
                    generationJob.getId(),
                    defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                    rawPromptFailureOutput(ex, PromptTemplateType.SCRIPT_GENERATE.name(), providerOutputForDebug, aiOutputDiagnostics)
            );
            throw ex;
        }
    }

    @Transactional
    public GeneratedScriptResponse saveEditedScript(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            UUID scriptId,
            SaveGeneratedScriptRequest request,
            String tenantId,
            String userId
    ) {
        CreatorIdea storyIdea = getStoryIdeaForLockedBrief(lockedIdeaId, storyIdeaId, tenantId, userId);
        CreatorScript creatorScript = scriptRepository
                .findByIdAndTenantIdAndUserId(scriptId, storyIdea.getTenantId(), storyIdea.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated script was not found."));
        if (!lockedIdeaId.equals(creatorScript.getLockedIdeaId()) || !storyIdeaId.equals(creatorScript.getStoryIdeaId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script does not belong to the selected story idea.");
        }

        GeneratedScriptResponse.CinematicScript scriptJson = request == null ? null : request.scriptJson();
        List<GeneratedScriptResponse.CinematicShot> shots = scriptJson == null ? null : scriptJson.getShots();
        if ((shots == null || shots.isEmpty()) && request != null && request.scenes() != null) {
            shots = request.scenes();
        }
        if (scriptJson == null) {
            scriptJson = objectMapper.convertValue(creatorScript.getScriptPayload(), GeneratedScriptResponse.CinematicScript.class);
        }
        if (shots == null || shots.isEmpty()) {
            shots = objectMapper.convertValue(creatorScript.getShots(), new TypeReference<List<GeneratedScriptResponse.CinematicShot>>() {
            });
        }
        scriptJson.setShots(shots);
        if (scriptJson.getTotalShots() == null || scriptJson.getTotalShots() <= 0) {
            scriptJson.setTotalShots(shots.size());
        }
        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), scriptJson.getDuration());
        scriptJson.setDuration(durationSeconds);
        String dialogueLanguage = normalizeDialogueLanguage(defaultString(request == null ? null : request.dialogueLanguage(), defaultString(scriptJson.getDialogueLanguage(), creatorScript.getDialogueLanguage())));
        String screenType = normalizeScreenType(defaultString(request == null ? null : request.screenType(), defaultString(scriptJson.getScreenType(), creatorScript.getScreenType())));
        scriptJson.setDialogueLanguage(dialogueLanguage);
        scriptJson.setScreenType(screenType);
        stripEmbeddedProductionPlanTags(scriptJson);

        String title = defaultString(request == null ? null : request.title(), defaultString(scriptJson.getProjectTitle(), storyIdea.getTitle()));
        scriptJson.setProjectTitle(title);
        String scriptText = defaultString(request == null ? null : request.script(), buildScriptText(storyIdea, shots));
        String categoryCode = defaultString(scriptJson.getCategory(), creatorScript.getCategoryCode());
        String inferredTone = defaultString(scriptJson.getInferredTone(), inferTone(scriptText, categoryCode));
        enrichAudioAndMusicDesign(scriptJson, categoryCode, inferredTone);
        Map<String, Object> scriptPayloadMap = toMap(scriptJson);
        GeneratedStoryScriptResponse.StoryScript storyScript = readStoryScriptFromIdea(storyIdea);
        putStoryStructure(scriptPayloadMap, storyScript);
        List<Map<String, Object>> shotPayloads = toMapList(shots);

        creatorScript.setTitle(title);
        creatorScript.setDurationSeconds(durationSeconds);
        creatorScript.setFormatTier(scriptJson.getFormatTier());
        creatorScript.setActStructure(scriptJson.getActStructure());
        creatorScript.setBudgetTier(scriptJson.getBudgetTier());
        creatorScript.setTotalShots(scriptJson.getTotalShots());
        creatorScript.setSceneCount(scriptJson.getSceneCount());
        creatorScript.setSequenceCount(scriptJson.getSequenceCount());
        creatorScript.setDialogueLanguage(dialogueLanguage);
        creatorScript.setScreenType(screenType);
        creatorScript.setScriptText(scriptText);
        creatorScript.setScriptPayload(scriptPayloadMap);
        creatorScript.setShots(shotPayloads);
        creatorScript.setStatus("EDITED");
        creatorScript.setUpdatedAt(OffsetDateTime.now());
        CreatorScript savedScript = scriptRepository.save(creatorScript);
        scriptStructureService.syncFromStoryScript(savedScript, storyScript);
        ProductionPlanGenerationResult productionPlanResult = productionPlanNotStarted();
        List<ShotProductionPlanTagResponse> productionPlanTags = productionPlanResult.tags();
        enrichScriptPayloadWithProductionPlanTags(scriptJson, productionPlanTags);
        Map<String, Object> enrichedScriptPayloadMap = toMap(scriptJson);
        putStoryStructure(enrichedScriptPayloadMap, storyScript);
        putProductionPlanStatus(enrichedScriptPayloadMap, productionPlanResult);
        List<Map<String, Object>> enrichedShotPayloads = toMapList(shots);
        savedScript.setScriptPayload(enrichedScriptPayloadMap);
        savedScript.setShots(enrichedShotPayloads);
        savedScript.setUpdatedAt(OffsetDateTime.now());
        savedScript = scriptRepository.save(savedScript);
        scriptStructureService.syncScreenplayShots(savedScript, enrichedScriptPayloadMap, enrichedShotPayloads);

        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("scriptId", savedScript.getId().toString());
        context.put("scriptEditedAt", savedScript.getUpdatedAt().toString());
        context.put("scriptSceneCount", shots.size());
        context.put("scriptDurationSeconds", durationSeconds);
        context.put("scriptDialogueLanguage", dialogueLanguage);
        context.put("scriptScreenType", screenType);
        context.put("productionPlanTagCount", productionPlanTags.size());
        context.put("productionPlanStatus", productionPlanResult.status());
        if (!productionPlanResult.error().isBlank()) {
            context.put("productionPlanError", productionPlanResult.error());
        }
        context.put("productionPlanGeneratedAt", OffsetDateTime.now().toString());

        storyIdea.setTitle(title);
        storyIdea.setScript(scriptText);
        storyIdea.setScenes(enrichedShotPayloads);
        storyIdea.setDurationSeconds(durationSeconds);
        storyIdea.setSelectionContext(context);
        storyIdea.setStatus("SCRIPT_EDITED");
        storyIdea.setSaved(true);
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        CreatorIdea savedIdea = ideaRepository.save(storyIdea);
        linkProjectSelectedIdea(savedIdea);

        return GeneratedScriptResponse.builder()
                .scriptId(savedScript.getId())
                .ideaId(savedIdea.getId())
                .lockedIdeaId(lockedIdeaId)
                .projectId(savedIdea.getProjectId())
                .promptRunId(savedScript.getPromptRunId())
                .title(savedIdea.getTitle())
                .script(savedIdea.getScript())
                .scriptJson(scriptJson)
                .scenes(shots)
                .productionPlanTags(productionPlanTags)
                .productionPlanStatus(productionPlanResult.status())
                .productionPlanError(productionPlanResult.error())
                .productionPlanDebug(productionPlanResult.debug())
                .durationSeconds(durationSeconds)
                .status(savedIdea.getStatus())
                .generatedAt(savedIdea.getUpdatedAt())
                .build();
    }

    private CreatorIdea getStoryIdeaForLockedBrief(UUID lockedIdeaId, UUID storyIdeaId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = ideaRepository.findById(lockedIdeaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found."));
        if (!safeTenantId.equals(lockedIdea.getTenantId()) || !safeUserId.equals(lockedIdea.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Locked idea was not found.");
        }
        CreatorIdea storyIdea = ideaRepository.findById(storyIdeaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Story idea was not found."));
        if (!safeTenantId.equals(storyIdea.getTenantId()) || !safeUserId.equals(storyIdea.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story idea was not found.");
        }
        Map<String, Object> context = storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext();
        if (!lockedIdeaId.toString().equals(String.valueOf(context.get("parentLockedIdeaId")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Story idea does not belong to the locked brief.");
        }
        storyIdea = ensureProjectOnStoryIdea(lockedIdea, storyIdea);
        return storyIdea;
    }

    private IdeaGenerationResult ensureGeneratedIdeas(CreatorIdea lockedIdea, UUID generationJobId) {
        Page<CreatorIdea> existing = ideaRepository.findGeneratedIdeasForLockedBrief(
                lockedIdea.getId(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                PageRequest.of(0, GENERATED_IDEA_COUNT)
        );
        int existingCount = (int) existing.getTotalElements();
        if (existingCount >= GENERATED_IDEA_COUNT) {
            return new IdeaGenerationResult(0, List.of(), List.of());
        }

        int targetCount = GENERATED_IDEA_COUNT - existingCount;
        Map<String, Object> sourceBrief = buildSourceBrief(lockedIdea);
        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.IDEA_GENERATE.name());

        List<GeneratedIdeaCandidate> generatedCandidates = new ArrayList<>();
        List<UUID> promptRunIds = new ArrayList<>();
        List<Map<String, Object>> providerOutputs = new ArrayList<>();
        int maxAttempts = Math.max(4, ((targetCount + GENERATED_IDEA_AI_BATCH_SIZE - 1) / GENERATED_IDEA_AI_BATCH_SIZE) + 2);

        for (int attempt = 0; generatedCandidates.size() < targetCount && attempt < maxAttempts; attempt++) {
            int batchSize = Math.min(GENERATED_IDEA_AI_BATCH_SIZE, targetCount - generatedCandidates.size());
            AiIdeaBatchResult batchResult = generateIdeaBatch(
                    lockedIdea,
                    generationJobId,
                    template,
                    sourceBrief,
                    existingCount,
                    generatedCandidates.size(),
                    batchSize
            );
            if (batchResult.promptRunId() != null) {
                promptRunIds.add(batchResult.promptRunId());
            }
            if (!batchResult.providerOutput().isEmpty()) {
                providerOutputs.add(batchResult.providerOutput());
            }

            int beforeBatch = generatedCandidates.size();
            for (IdeaCandidate candidate : batchResult.candidates()) {
                if (generatedCandidates.size() >= targetCount) {
                    break;
                }
                generatedCandidates.add(new GeneratedIdeaCandidate(candidate, batchResult.promptRunId()));
            }
            if (generatedCandidates.size() == beforeBatch) {
                break;
            }
        }

        while (generatedCandidates.size() < targetCount) {
            int ideaNumber = existingCount + generatedCandidates.size() + 1;
            generatedCandidates.add(new GeneratedIdeaCandidate(fallbackIdeaCandidate(lockedIdea, ideaNumber), null));
        }

        OffsetDateTime now = OffsetDateTime.now();
        int generatedCount = 0;
        for (int index = 0; index < generatedCandidates.size(); index++) {
            int ideaNumber = existingCount + index + 1;
            GeneratedIdeaCandidate generated = generatedCandidates.get(index);
            IdeaCandidate candidate = generated.candidate();
            Map<String, Object> context = buildGeneratedContext(lockedIdea, ideaNumber, candidate, generated.promptRunId(), generationJobId);
            ideaRepository.save(CreatorIdea.builder()
                    .tenantId(lockedIdea.getTenantId())
                    .userId(lockedIdea.getUserId())
                    .projectId(lockedIdea.getProjectId())
                    .trendId(lockedIdea.getTrendId())
                    .source("AI_FROM_LOCKED_BRIEF")
                    .title(truncate(candidate.title(), 240))
                    .summary(candidate.summary())
                    .durationSeconds(lockedIdea.getDurationSeconds())
                    .status("DRAFT")
                    .generationJobId(generationJobId)
                    .promptRunId(generated.promptRunId())
                    .selectionContext(context)
                    .createdAt(now.plusNanos(ideaNumber))
                    .updatedAt(now.plusNanos(ideaNumber))
                    .build());
            generatedCount++;
        }
        return new IdeaGenerationResult(generatedCount, promptRunIds, providerOutputs);
    }

    private AiIdeaBatchResult generateIdeaBatch(
            CreatorIdea lockedIdea,
            UUID generationJobId,
            CreatorPromptTemplate template,
            Map<String, Object> sourceBrief,
            int existingCount,
            int alreadyGeneratedCount,
            int candidateCount
    ) {
        List<String> batchAngles = ideaAnglesForBatch(existingCount + alreadyGeneratedCount, candidateCount);
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("lockedIdeaId", lockedIdea.getId().toString());
        inputSnapshot.put("candidateCount", candidateCount);
        inputSnapshot.put("totalCandidateTarget", GENERATED_IDEA_COUNT);
        inputSnapshot.put("existingCandidateCount", existingCount + alreadyGeneratedCount);
        inputSnapshot.put("durationSeconds", lockedIdea.getDurationSeconds() == null ? 30 : lockedIdea.getDurationSeconds());
        inputSnapshot.put("lockedBrief", sourceBrief);
        inputSnapshot.put("ideaAngles", batchAngles);
        inputSnapshot.put("generationRules", List.of(
                "Generate distinct short-form story ideas from the locked brief.",
                "Each idea must be practical for a beginner creator using a phone.",
                "Do not return a screenplay or storyboard yet.",
                "Return JSON only with an ideas array."
        ));

        Map<String, Object> renderVariables = new LinkedHashMap<>(inputSnapshot);
        renderVariables.put("lockedBriefJson", toJson(sourceBrief));
        renderVariables.put("ideaAnglesJson", toJson(batchAngles));
        String renderedPrompt = promptTemplateService.render(template, renderVariables);

        Map<String, Object> providerInput = new LinkedHashMap<>(inputSnapshot);
        providerInput.put("renderedPrompt", renderedPrompt);

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getProjectId(),
                generationJobId,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse =
                creatorAiService.generateMetered(PromptTemplateType.IDEA_GENERATE.name(), providerInput, usageContext);
        Map<String, Object> providerOutput = aiResponse.output();

        List<IdeaCandidate> candidates = ideaCandidates(providerOutput, candidateCount);
        List<Map<String, Object>> normalizedIdeas = candidates.stream()
                .map(IdeaCandidate::toMap)
                .toList();
        Map<String, Object> promptOutputPayload = new LinkedHashMap<>();
        promptOutputPayload.put("ideas", normalizedIdeas);
        promptOutputPayload.put("providerOutput", providerOutput);

        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(lockedIdea.getTenantId())
                .userId(lockedIdea.getUserId())
                .projectId(lockedIdea.getProjectId())
                .jobId(generationJobId)
                .promptTemplateId(template.getId())
                .promptTemplateKey(template.getTemplateKey())
                .promptTemplateVersion(template.getVersion())
                .renderedPrompt(renderedPrompt)
                .inputSnapshot(inputSnapshot)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(promptOutputPayload)
                .tokenMetadata(aiResponse.tokenMetadata())
                .costMetadata(aiResponse.costMetadata())
                .status("COMPLETED")
                .completedAt(OffsetDateTime.now())
                .build());
        creatorAiService.publishBillingDebit(
                PromptTemplateType.IDEA_GENERATE.name(),
                aiResponse,
                usageContext.withPromptRunId(promptRun.getId())
        );

        return new AiIdeaBatchResult(candidates, promptRun.getId(), providerOutput);
    }

    private List<String> ideaAnglesForBatch(int offset, int count) {
        List<String> angles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            angles.add(IDEA_ANGLES.get((offset + index) % IDEA_ANGLES.size()));
        }
        return angles;
    }

    private Map<String, Object> buildGeneratedContext(
            CreatorIdea lockedIdea,
            int ideaNumber,
            IdeaCandidate candidate,
            UUID promptRunId,
            UUID generationJobId
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("parentLockedIdeaId", lockedIdea.getId().toString());
        context.put("generatedIndex", ideaNumber);
        context.put("angle", candidate.angle());
        context.put("sourceBrief", buildSourceBrief(lockedIdea));
        context.put("hashtags", candidate.hashtags().isEmpty() ? hashtagsFor(lockedIdea, candidate.angle()) : candidate.hashtags());
        context.put("creativeNotes", candidate.creativeNotes());
        context.put("provider", creatorAiService.providerName());
        context.put("model", creatorAiService.modelName());
        if (promptRunId != null) {
            context.put("promptRunId", promptRunId.toString());
        }
        context.put("generationJobId", generationJobId.toString());
        context.put("aiGenerated", promptRunId != null);
        return context;
    }

    private Map<String, Object> buildSourceBrief(CreatorIdea lockedIdea) {
        Map<String, Object> sourceBrief = new LinkedHashMap<>();
        sourceBrief.put("title", lockedIdea.getTitle());
        sourceBrief.put("summary", defaultString(lockedIdea.getSummary(), ""));
        sourceBrief.put("source", lockedIdea.getSource());
        sourceBrief.put("durationSeconds", lockedIdea.getDurationSeconds() == null ? 30 : lockedIdea.getDurationSeconds());

        Map<String, Object> selectionContext = lockedIdea.getSelectionContext() == null ? Map.of() : lockedIdea.getSelectionContext();
        putIfPresent(sourceBrief, "platformCode", selectionContext.get("platformCode"));
        putIfPresent(sourceBrief, "categoryCode", selectionContext.get("categoryCode"));
        putIfPresent(sourceBrief, "countryCode", selectionContext.get("countryCode"));
        putIfPresent(sourceBrief, "timeframe", selectionContext.get("timeframe"));
        putIfPresent(sourceBrief, "selectionPayload", selectionContext.get("selectionPayload"));
        putIfPresent(sourceBrief, "trend", selectionContext.get("trend"));
        return sourceBrief;
    }

    private List<IdeaCandidate> ideaCandidates(Map<String, Object> providerOutput, int limit) {
        List<Map<String, Object>> rawIdeas = firstMapList(providerOutput, "ideas", "storyIdeas", "candidates", "items", "results");
        if (rawIdeas.isEmpty()) {
            rawIdeas = rawTextIdeaMaps(providerOutput == null ? null : providerOutput.get("rawText"));
        }
        List<IdeaCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> rawIdea : rawIdeas) {
            if (candidates.size() >= limit) {
                break;
            }
            String title = stringValue(firstValue(rawIdea, "title", "ideaTitle", "name", "hook"), "");
            String summary = stringValue(firstValue(rawIdea, "description", "summary", "idea", "concept", "logline"), "");
            if (title.isBlank() && !summary.isBlank()) {
                title = truncate(summary, 80);
            }
            if (summary.isBlank() && !title.isBlank()) {
                summary = title;
            }
            if (title.isBlank() || summary.isBlank()) {
                continue;
            }

            List<String> hashtags = stringList(firstValue(rawIdea, "hashtags", "tags", "suggestedTags"));
            Map<String, Object> creativeNotes = new LinkedHashMap<>(mapValue(rawIdea.get("creativeNotes")));
            copyIfPresent(rawIdea, creativeNotes, "hook");
            copyIfPresent(rawIdea, creativeNotes, "targetEmotion");
            copyIfPresent(rawIdea, creativeNotes, "storyShape");
            copyIfPresent(rawIdea, creativeNotes, "selectionReason");
            copyIfPresent(rawIdea, creativeNotes, "whyItWorks");
            copyIfPresent(rawIdea, creativeNotes, "openingVisual");
            copyIfPresent(rawIdea, creativeNotes, "audiencePromise");
            creativeNotes.putIfAbsent("hook", title);
            creativeNotes.putIfAbsent("selectionReason", "AI generated from the locked creator brief.");

            candidates.add(new IdeaCandidate(
                    truncate(title, 240),
                    truncate(summary, 1000),
                    hashtags,
                    creativeNotes
            ));
        }
        return candidates;
    }

    private IdeaCandidate fallbackIdeaCandidate(CreatorIdea lockedIdea, int ideaNumber) {
        String angle = IDEA_ANGLES.get((ideaNumber - 1) % IDEA_ANGLES.size());
        Map<String, Object> creativeNotes = new LinkedHashMap<>();
        creativeNotes.put("hook", angle);
        creativeNotes.put("targetEmotion", targetEmotionFor(angle));
        creativeNotes.put("storyShape", storyShapeFor(angle));
        creativeNotes.put("selectionReason", "Fallback idea added because the AI provider returned fewer candidates than requested.");
        return new IdeaCandidate(
                buildTitle(lockedIdea, angle, ideaNumber),
                buildSummary(lockedIdea, angle),
                hashtagsFor(lockedIdea, angle),
                creativeNotes
        );
    }

    private String buildTitle(CreatorIdea lockedIdea, String angle, int ideaNumber) {
        String base = cleanBaseTitle(lockedIdea.getTitle());
        return truncate("%02d. %s: %s".formatted(ideaNumber, angle, base), 240);
    }

    private String buildSummary(CreatorIdea lockedIdea, String angle) {
        String brief = defaultString(lockedIdea.getSummary(), lockedIdea.getTitle());
        return switch (angle.toLowerCase(Locale.ROOT)) {
            case "contrarian opener" -> "Open against the obvious take, then show why this brief matters in a surprising short-form payoff.";
            case "beginner mistake" -> "Turn the brief into a practical mistake viewers recognize immediately, then resolve it with one simple move.";
            case "before-after reveal" -> "Use the locked brief as a fast before-after arc with a clear visual transformation and saveable ending.";
            case "silent reaction" -> "Make the hook a facial reaction first, then reveal the brief through text, action, and one punchy line.";
            case "pov comedy beat" -> "Frame the brief as a POV moment with a relatable setup, awkward middle beat, and clean punchline.";
            default -> "Use the locked brief as the source: " + truncate(brief, 220);
        };
    }

    private GeneratedStoryScriptResponse.StoryScript buildStoryScriptPayload(
            CreatorIdea storyIdea,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType
    ) {
        String title = cleanBaseTitle(storyIdea.getTitle());
        List<GeneratedStoryScriptResponse.CharacterProfile> characters = characterProfilesFor(storyIdea, categoryCode, inferredTone, dialogueLanguage);
        return GeneratedStoryScriptResponse.StoryScript.builder()
                .projectTitle(title)
                .duration(durationSeconds)
                .category(categoryCode)
                .dialogueLanguage(dialogueLanguage)
                .screenType(screenType)
                .logline("A beginner-friendly short about " + title + " where one small decision changes the emotional direction of the moment.")
                .centralConflict("The main character wants the payoff of the idea, but hesitation, social pressure, or a familiar excuse blocks the first step.")
                .storyline("The story opens at the exact decision point, introduces the creator's internal block through a relatable character moment, adds a small external nudge, then resolves with one achievable action that creates a visible emotional shift.")
                .emotionalArc("Doubt -> recognition -> small action -> confidence or comic relief")
                .hook("Start on the character caught mid-thought before explaining the idea.")
                .endingPayoff("The character lands a clear final line or look that makes the idea feel saveable and repeatable.")
                .setting(environmentFor(categoryCode))
                .inferredTone(inferredTone)
                .characters(characters)
                .beats(storyBeatsFor(durationSeconds, characters))
                .build();
    }

    private GeneratedStoryScriptResponse.StoryScript resolveStoryScriptPayload(
            Map<String, Object> providerOutput,
            CreatorIdea storyIdea,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType,
            Map<String, Object> diagnostics
    ) {
        GeneratedStoryScriptResponse.StoryScript fallback = buildStoryScriptPayload(
                storyIdea,
                durationSeconds,
                categoryCode,
                inferredTone,
                dialogueLanguage,
                screenType
        );
        populateAiOutputDiagnostics(diagnostics, PromptTemplateType.STORY_SCRIPT_GENERATE.name(), providerOutput);
        Map<String, Object> payload = extractStructuredProviderPayload(providerOutput, "scriptJson", "storyScript", "script", "story");
        populatePayloadDiagnostics(diagnostics, payload);
        if (!payload.isEmpty()) {
            try {
                Map<String, Object> retainedPayload = storyScriptFields(payload);
                diagnostics.put("retainedPayloadKeys", retainedPayload.keySet().stream().toList());
                GeneratedStoryScriptResponse.StoryScript storyScript =
                        objectMapper.convertValue(retainedPayload, GeneratedStoryScriptResponse.StoryScript.class);
                String validationReason = storyScriptValidationReason(storyScript);
                if (validationReason.isBlank()) {
                    applyStoryScriptDefaults(storyScript, fallback, storyIdea, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType);
                    diagnostics.put("usedAiOutput", true);
                    diagnostics.put("fallbackUsed", false);
                    log.info("Creator story script AI output accepted storyIdeaId={} payloadKeys={}",
                            storyIdea.getId(),
                            payload.keySet());
                    return storyScript;
                }
                diagnostics.put("failureReason", validationReason);
            } catch (IllegalArgumentException ex) {
                diagnostics.put("failureReason", "CONVERSION_FAILED: " + truncate(defaultString(ex.getMessage(), ex.getClass().getSimpleName()), 500));
                log.warn("Creator story script AI output could not be converted storyIdeaId={} reason={}", storyIdea.getId(), ex.getMessage());
            }
        } else {
            diagnostics.put("failureReason", rawTextFailureReason(providerOutput, "NO_STRUCTURED_PROVIDER_OUTPUT"));
        }

        diagnostics.put("usedAiOutput", false);
        diagnostics.put("fallbackUsed", false);
        diagnostics.putIfAbsent("failureReason", "MISSING_USABLE_STORY_SCRIPT");
        log.error("Creator story script AI output rejected storyIdeaId={} reason={} providerKeys={} payloadKeys={} providerPreview={}",
                storyIdea.getId(),
                diagnostics.get("failureReason"),
                diagnostics.get("providerKeys"),
                diagnostics.get("payloadKeys"),
                diagnostics.get("providerOutputPreview"));
        throw storyScriptGenerationFailure(storyIdea, diagnostics, providerOutput);
    }

    private ResponseStatusException storyScriptGenerationFailure(CreatorIdea storyIdea, Map<String, Object> diagnostics, Map<String, Object> providerOutput) {
        String reason = stringValue(diagnostics == null ? null : diagnostics.get("failureReason"), "MISSING_USABLE_STORY_SCRIPT");
        String payloadKeys = String.valueOf(diagnostics == null ? List.of() : diagnostics.getOrDefault("payloadKeys", List.of()));
        String message = "Storyline generation failed because the AI did not return complete parseable story JSON. "
                + "Reason: " + reason + ". Payload keys: " + payloadKeys + ". "
                + "Please regenerate with complete JSON containing projectTitle, logline, storyline, centralConflict, emotionalArc, hook, endingPayoff, characters, and beats. "
                + "StoryIdeaId=" + (storyIdea == null ? "" : storyIdea.getId()) + ".";
        return new CreatorAiOutputException(
                HttpStatus.BAD_GATEWAY,
                message,
                rawPromptDebugPayload(PromptTemplateType.STORY_SCRIPT_GENERATE.name(), providerOutput, diagnostics)
        );
    }

    private boolean isUsableStoryScript(GeneratedStoryScriptResponse.StoryScript storyScript) {
        return storyScriptValidationReason(storyScript).isBlank();
    }

    private String storyScriptValidationReason(GeneratedStoryScriptResponse.StoryScript storyScript) {
        if (storyScript == null) {
            return "NULL_STORY_SCRIPT";
        }
        boolean hasStory = !defaultString(storyScript.getProjectTitle(), "").isBlank()
                || !defaultString(storyScript.getLogline(), "").isBlank()
                || !defaultString(storyScript.getStoryline(), "").isBlank();
        boolean hasStructure = storyScript.getCharacters() != null && !storyScript.getCharacters().isEmpty()
                || storyScript.getBeats() != null && !storyScript.getBeats().isEmpty();
        if (!hasStory) {
            return "MISSING_PROJECT_TITLE_LOGLINE_AND_STORYLINE";
        }
        if (!hasStructure) {
            return "MISSING_CHARACTERS_AND_BEATS";
        }
        return "";
    }

    private void applyStoryScriptDefaults(
            GeneratedStoryScriptResponse.StoryScript storyScript,
            GeneratedStoryScriptResponse.StoryScript fallback,
            CreatorIdea storyIdea,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType
    ) {
        storyScript.setProjectTitle(defaultString(storyScript.getProjectTitle(), fallback.getProjectTitle()));
        storyScript.setDuration(storyScript.getDuration() == null || storyScript.getDuration() <= 0 ? durationSeconds : storyScript.getDuration());
        storyScript.setCategory(defaultString(storyScript.getCategory(), categoryCode));
        storyScript.setDialogueLanguage(defaultString(storyScript.getDialogueLanguage(), dialogueLanguage));
        storyScript.setScreenType(normalizeScreenType(defaultString(storyScript.getScreenType(), screenType)));
        storyScript.setLogline(defaultString(storyScript.getLogline(), fallback.getLogline()));
        storyScript.setCentralConflict(defaultString(storyScript.getCentralConflict(), fallback.getCentralConflict()));
        storyScript.setStoryline(defaultString(storyScript.getStoryline(), fallback.getStoryline()));
        storyScript.setEmotionalArc(defaultString(storyScript.getEmotionalArc(), fallback.getEmotionalArc()));
        storyScript.setHook(defaultString(storyScript.getHook(), fallback.getHook()));
        storyScript.setEndingPayoff(defaultString(storyScript.getEndingPayoff(), fallback.getEndingPayoff()));
        storyScript.setSetting(defaultString(storyScript.getSetting(), fallback.getSetting()));
        storyScript.setInferredTone(defaultString(storyScript.getInferredTone(), inferredTone));
        if (storyScript.getCharacters() == null || storyScript.getCharacters().isEmpty()) {
            storyScript.setCharacters(fallback.getCharacters());
        }
        if (storyScript.getBeats() == null || storyScript.getBeats().isEmpty()) {
            storyScript.setBeats(storyBeatsFor(durationSeconds, storyScript.getCharacters()));
        }
    }

    private GeneratedScriptResponse.CinematicScript resolveCinematicScriptPayload(
            Map<String, Object> providerOutput,
            CreatorIdea storyIdea,
            GeneratedStoryScriptResponse.StoryScript storyScript,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType,
            Map<String, Object> diagnostics
    ) {
        GeneratedScriptResponse.CinematicScript fallback = buildCinematicScriptPayload(
                storyIdea,
                storyScript,
                durationSeconds,
                categoryCode,
                inferredTone,
                dialogueLanguage,
                screenType
        );
        populateAiOutputDiagnostics(diagnostics, PromptTemplateType.SCRIPT_GENERATE.name(), providerOutput);
        Map<String, Object> payload = extractStructuredProviderPayload(providerOutput, "scriptJson", "screenplay", "cinematicScript", "script");
        populatePayloadDiagnostics(diagnostics, payload);
        if (!payload.isEmpty()) {
            try {
                Map<String, Object> retainedPayload = cinematicScriptFields(payload);
                diagnostics.put("retainedPayloadKeys", retainedPayload.keySet().stream().toList());
                GeneratedScriptResponse.CinematicScript scriptPayload =
                        objectMapper.convertValue(retainedPayload, GeneratedScriptResponse.CinematicScript.class);
                if (scriptPayload != null && (scriptPayload.getShots() == null || scriptPayload.getShots().isEmpty())) {
                    scriptPayload.setShots(flattenCinematicShots(retainedPayload));
                }
                if (scriptPayload != null && scriptPayload.getShots() != null && !scriptPayload.getShots().isEmpty()) {
                    applyCinematicScriptDefaults(scriptPayload, fallback, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType);
                    diagnostics.put("usedAiOutput", true);
                    diagnostics.put("fallbackUsed", false);
                    log.info("Creator screenplay AI output accepted storyIdeaId={} payloadKeys={}",
                            storyIdea.getId(),
                            payload.keySet());
                    return scriptPayload;
                }
                diagnostics.put("failureReason", scriptPayload == null ? "NULL_SCREENPLAY" : missingShotsFailureReason(providerOutput, retainedPayload));
            } catch (IllegalArgumentException ex) {
                diagnostics.put("failureReason", "CONVERSION_FAILED: " + truncate(defaultString(ex.getMessage(), ex.getClass().getSimpleName()), 500));
                log.warn("Creator screenplay AI output could not be converted storyIdeaId={} reason={}", storyIdea.getId(), ex.getMessage());
            }
        } else {
            diagnostics.put("failureReason", rawTextFailureReason(providerOutput, "NO_STRUCTURED_PROVIDER_OUTPUT"));
        }

        diagnostics.put("usedAiOutput", false);
        diagnostics.put("fallbackUsed", false);
        diagnostics.putIfAbsent("failureReason", "MISSING_USABLE_SCREENPLAY");
        log.error("Creator screenplay AI output rejected storyIdeaId={} reason={} providerKeys={} payloadKeys={} providerPreview={}",
                storyIdea.getId(),
                diagnostics.get("failureReason"),
                diagnostics.get("providerKeys"),
                diagnostics.get("payloadKeys"),
                diagnostics.get("providerOutputPreview"));
        throw screenplayGenerationFailure(storyIdea, diagnostics, providerOutput);
    }

    private ResponseStatusException screenplayGenerationFailure(CreatorIdea storyIdea, Map<String, Object> diagnostics, Map<String, Object> providerOutput) {
        String reason = stringValue(diagnostics == null ? null : diagnostics.get("failureReason"), "MISSING_USABLE_SCREENPLAY");
        String payloadKeys = String.valueOf(diagnostics == null ? List.of() : diagnostics.getOrDefault("payloadKeys", List.of()));
        String message = "Screenplay generation failed because the AI did not return complete parseable screenplay JSON with shots. "
                + "Reason: " + reason + ". Payload keys: " + payloadKeys + ". "
                + "Please regenerate with a complete screenplay JSON that includes shots/scenes/sequences, exact numeric timing, character continuity, blockingNotes, lighting, structured soundDesign with ambient_bed and sync_hit, backgroundMusicPlan/backgroundMusicCue, captionTrack, safetyFlags, resourceRequirements, and postProductionNotes. "
                + "StoryIdeaId=" + (storyIdea == null ? "" : storyIdea.getId()) + ".";
        return new CreatorAiOutputException(
                HttpStatus.BAD_GATEWAY,
                message,
                rawPromptDebugPayload(PromptTemplateType.SCRIPT_GENERATE.name(), providerOutput, diagnostics)
        );
    }

    private void applyCinematicScriptDefaults(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            GeneratedScriptResponse.CinematicScript fallback,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType
    ) {
        scriptPayload.setProjectTitle(defaultString(scriptPayload.getProjectTitle(), fallback.getProjectTitle()));
        scriptPayload.setDuration(scriptPayload.getDuration() == null || scriptPayload.getDuration() <= 0 ? durationSeconds : scriptPayload.getDuration());
        scriptPayload.setTotalShots(scriptPayload.getTotalShots() == null || scriptPayload.getTotalShots() <= 0
                ? scriptPayload.getShots().size()
                : scriptPayload.getTotalShots());
        scriptPayload.setFormatTier(defaultString(scriptPayload.getFormatTier(), formatTierFor(durationSeconds)));
        scriptPayload.setActStructure(defaultString(scriptPayload.getActStructure(), actStructureFor(scriptPayload.getFormatTier())));
        scriptPayload.setBudgetTier(defaultString(scriptPayload.getBudgetTier(), inferBudgetTier(durationSeconds)));
        scriptPayload.setSceneCount(scriptPayload.getSceneCount() == null ? mapListValue(scriptPayload.getScenes()).size() : scriptPayload.getSceneCount());
        scriptPayload.setSequenceCount(scriptPayload.getSequenceCount() == null ? mapListValue(scriptPayload.getSequences()).size() : scriptPayload.getSequenceCount());
        scriptPayload.setPacingStyle(defaultString(scriptPayload.getPacingStyle(), fallback.getPacingStyle()));
        scriptPayload.setEmotionalArc(defaultString(scriptPayload.getEmotionalArc(), fallback.getEmotionalArc()));
        scriptPayload.setHookStrategy(defaultString(scriptPayload.getHookStrategy(), fallback.getHookStrategy()));
        scriptPayload.setCreatorFitReasoning(defaultString(scriptPayload.getCreatorFitReasoning(), fallback.getCreatorFitReasoning()));
        scriptPayload.setAudienceFitReasoning(defaultString(scriptPayload.getAudienceFitReasoning(), fallback.getAudienceFitReasoning()));
        scriptPayload.setOverallExecutionDifficulty(defaultString(scriptPayload.getOverallExecutionDifficulty(), fallback.getOverallExecutionDifficulty()));
        scriptPayload.setCategory(defaultString(scriptPayload.getCategory(), categoryCode));
        scriptPayload.setInferredTone(defaultString(scriptPayload.getInferredTone(), inferredTone));
        scriptPayload.setDialogueLanguage(defaultString(scriptPayload.getDialogueLanguage(), dialogueLanguage));
        scriptPayload.setScreenType(normalizeScreenType(defaultString(scriptPayload.getScreenType(), screenType)));
    }

    private List<GeneratedScriptResponse.CinematicShot> flattenCinematicShots(Map<String, Object> payload) {
        List<Map<String, Object>> shotMaps = new ArrayList<>();
        shotMaps.addAll(mapListValue(payload.get("shots")));
        if (shotMaps.isEmpty()) {
            for (Map<String, Object> scene : mapListValue(payload.get("scenes"))) {
                Integer sceneNumber = integerValue(scene.get("sceneNumber"), null);
                for (Map<String, Object> shot : mapListValue(scene.get("shots"))) {
                    Map<String, Object> copy = new LinkedHashMap<>(shot);
                    copy.putIfAbsent("sceneNumber", sceneNumber);
                    shotMaps.add(copy);
                }
            }
        }
        if (shotMaps.isEmpty()) {
            for (Map<String, Object> sequence : mapListValue(payload.get("sequences"))) {
                Integer sequenceNumber = integerValue(sequence.get("sequenceNumber"), null);
                for (Map<String, Object> scene : mapListValue(sequence.get("scenes"))) {
                    Integer sceneNumber = integerValue(scene.get("sceneNumber"), null);
                    for (Map<String, Object> shot : mapListValue(scene.get("shots"))) {
                        Map<String, Object> copy = new LinkedHashMap<>(shot);
                        copy.putIfAbsent("sequenceNumber", sequenceNumber);
                        copy.putIfAbsent("sceneNumber", sceneNumber);
                        shotMaps.add(copy);
                    }
                }
            }
        }
        return shotMaps.stream()
                .map(shot -> objectMapper.convertValue(shot, GeneratedScriptResponse.CinematicShot.class))
                .toList();
    }

    private Map<String, Object> extractStructuredProviderPayload(Map<String, Object> providerOutput, String... wrapperKeys) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return Map.of();
        }
        for (String key : wrapperKeys) {
            Map<String, Object> nested = mapValue(providerOutput.get(key));
            if (!nested.isEmpty()) {
                return nested;
            }
        }

        Map<String, Object> rawTextPayload = rawTextJsonMap(providerOutput.get("rawText"));
        if (!rawTextPayload.isEmpty()) {
            for (String key : wrapperKeys) {
                Map<String, Object> nested = mapValue(rawTextPayload.get(key));
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return rawTextPayload;
        }

        return providerPayloadWithoutMetadata(providerOutput);
    }

    private Map<String, Object> providerPayloadWithoutMetadata(Map<String, Object> providerOutput) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>(providerOutput);
        payload.remove("rawText");
        payload.remove("provider");
        payload.remove("model");
        payload.remove("promptType");
        payload.remove("status");
        payload.remove("responseId");
        payload.remove("tokenUsage");
        payload.remove("promptFeedback");
        payload.remove("finishReason");
        payload.remove("finishReasons");
        return payload;
    }

    private boolean shouldRetryScreenplayGeneration(Map<String, Object> diagnostics) {
        String reason = stringValue(diagnostics == null ? null : diagnostics.get("failureReason")).toUpperCase(Locale.ROOT);
        return reason.contains("RAW_TEXT")
                || reason.contains("TRUNCATED")
                || reason.contains("MISSING_SHOTS")
                || reason.contains("NO_STRUCTURED")
                || reason.contains("CONVERSION_FAILED");
    }

    private Map<String, Object> compactScreenplayRetryInput(
            Map<String, Object> originalProviderInput,
            String originalRenderedPrompt,
            Map<String, Object> firstAttemptDiagnostics,
            int durationSeconds
    ) {
        Map<String, Object> retryInput = new LinkedHashMap<>(originalProviderInput == null ? Map.of() : originalProviderInput);
        String failureReason = stringValue(firstAttemptDiagnostics == null ? null : firstAttemptDiagnostics.get("failureReason"), "");
        String shotGuidance = compactShotGuidance(durationSeconds);

        Map<String, Object> retryContext = new LinkedHashMap<>(mapValue(retryInput.get("context")));
        retryContext.put("screenplayRetryMode", "COMPACT_JSON");
        retryContext.put("previousFailureReason", failureReason);
        retryContext.put("shotCountGuidance", shotGuidance);
        retryInput.put("context", retryContext);
        retryInput.put("screenplayRetryMode", "COMPACT_JSON");
        retryInput.put("previousFailureReason", failureReason);
        retryInput.put("renderedPrompt",
                defaultString(originalRenderedPrompt, stringValue(retryInput.get("renderedPrompt")))
                        + compactScreenplayRetryInstruction(failureReason, shotGuidance));
        return retryInput;
    }

    private String compactScreenplayRetryInstruction(String failureReason, String shotGuidance) {
        return "\n\nBACKEND RETRY MODE - COMPACT SCREENPLAY JSON\n"
                + "The previous response failed validation because: " + defaultString(failureReason, "UNKNOWN") + ".\n"
                + "Return one complete parseable JSON object now. Do not return markdown, prose, explanations, or partial JSON.\n"
                + "Compact the screenplay so it fits safely in the output budget:\n"
                + "- Use " + shotGuidance + ".\n"
                + "- Keep every string field to one short sentence. No paragraphs.\n"
                + "- Prefer top-level shots for micro_short, short_form, and medium_form.\n"
                + "- Keep continuityBible, soundDesignPlan, backgroundMusicPlan, resourceRequirements, and postProductionNotes present but concise.\n"
                + "- Every shot must still include numeric timing, characters, blockingNotes, lighting, soundDesign with ambient_bed and sync_hit, ambientBedDescription, syncHitDescription, backgroundMusicCue, captionTrack, safetyFlags, resourceRequirements, postProductionNotes, and sketchPrompt.\n"
                + "- If a detail is unknown, use empty string, empty array, empty object, 0, 0.0, or false. Do not omit keys.\n"
                + "- Close every array and object. The final character must be }.\n";
    }

    private String compactShotGuidance(int durationSeconds) {
        if (durationSeconds <= 20) {
            return "3 to 5 top-level shots";
        }
        if (durationSeconds <= 60) {
            return "5 to 9 top-level shots";
        }
        if (durationSeconds <= 180) {
            return "10 to 18 top-level shots, favoring the lower count when the story allows it";
        }
        if (durationSeconds <= 600) {
            return "scene groups with 25 to 50 total nested shots, favoring compact shot descriptions";
        }
        if (durationSeconds <= 1800) {
            return "scene groups with 80 to 160 total nested shots, using concise shot objects";
        }
        return "sequence groups with concise nested scenes and shots, using the minimum complete shot count for the story";
    }

    private Map<String, Object> storyScriptFields(Map<String, Object> payload) {
        return retainKeys(payload,
                "projectTitle",
                "duration",
                "category",
                "dialogueLanguage",
                "screenType",
                "logline",
                "centralConflict",
                "storyline",
                "emotionalArc",
                "hook",
                "endingPayoff",
                "setting",
                "inferredTone",
                "characters",
                "beats");
    }

    private Map<String, Object> cinematicScriptFields(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    private Map<String, Object> retainKeys(Map<String, Object> payload, String... keys) {
        Map<String, Object> retained = new LinkedHashMap<>();
        if (payload == null) {
            return retained;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                retained.put(key, value);
            }
        }
        return retained;
    }

    private void populateAiOutputDiagnostics(
            Map<String, Object> diagnostics,
            String promptType,
            Map<String, Object> providerOutput
    ) {
        if (diagnostics == null) {
            return;
        }
        diagnostics.put("promptType", promptType);
        diagnostics.put("providerKeys", providerOutput == null ? List.of() : providerOutput.keySet().stream().toList());
        diagnostics.put("hasRawText", providerOutput != null && providerOutput.get("rawText") != null);
        diagnostics.put("rawTextPreview", providerOutput == null ? "" : truncate(stringValue(providerOutput.get("rawText")), 4000));
        diagnostics.put("finishReason", providerOutput == null ? "" : stringValue(providerOutput.get("finishReason")));
        diagnostics.put("finishReasons", providerOutput == null ? List.of() : firstListValue(providerOutput.get("finishReasons")));
        diagnostics.put("tokenUsage", providerOutput == null ? Map.of() : mapValue(providerOutput.get("tokenUsage")));
        diagnostics.put("providerOutputPreview", truncate(toJson(providerOutput == null ? Map.of() : providerOutput), 4000));
    }

    private void populatePayloadDiagnostics(Map<String, Object> diagnostics, Map<String, Object> payload) {
        if (diagnostics == null) {
            return;
        }
        diagnostics.put("payloadKeys", payload == null ? List.of() : payload.keySet().stream().toList());
        diagnostics.put("payloadPreview", truncate(toJson(payload == null ? Map.of() : payload), 4000));
    }

    private String rawTextFailureReason(Map<String, Object> providerOutput, String fallback) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return fallback;
        }
        if (isMaxTokensFinish(providerOutput)) {
            return "RAW_TEXT_TRUNCATED_BY_MAX_OUTPUT_TOKENS";
        }
        if (providerOutput.get("rawText") != null) {
            return "RAW_TEXT_JSON_PARSE_FAILED";
        }
        return fallback;
    }

    private String missingShotsFailureReason(Map<String, Object> providerOutput, Map<String, Object> retainedPayload) {
        if (isMaxTokensFinish(providerOutput)) {
            return "RAW_TEXT_TRUNCATED_BEFORE_SHOTS";
        }
        if (providerOutput != null && providerOutput.get("rawText") != null && (retainedPayload == null || retainedPayload.isEmpty())) {
            return "RAW_TEXT_JSON_PARSE_FAILED";
        }
        return "MISSING_SHOTS";
    }

    private boolean isMaxTokensFinish(Map<String, Object> providerOutput) {
        String reason = stringValue(providerOutput == null ? null : providerOutput.get("finishReason")).toUpperCase(Locale.ROOT);
        if (reason.contains("MAX_TOKEN") || reason.contains("MAX_OUTPUT")) {
            return true;
        }
        for (Object item : firstListValue(providerOutput == null ? null : providerOutput.get("finishReasons"))) {
            String text = stringValue(item).toUpperCase(Locale.ROOT);
            if (text.contains("MAX_TOKEN") || text.contains("MAX_OUTPUT")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> rawPromptResponse(
            UUID promptRunId,
            String promptType,
            Map<String, Object> providerOutput,
            Map<String, Object> diagnostics
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (promptRunId != null) {
            response.put("promptRunId", promptRunId.toString());
        }
        response.put("promptType", defaultString(promptType, ""));
        response.put("provider", creatorAiService.providerName());
        response.put("model", creatorAiService.modelName());
        response.put("providerOutput", copyDebugMap(providerOutput));
        response.put("diagnostics", copyDebugMap(diagnostics));
        return response;
    }

    private Map<String, Object> rawPromptDebugPayload(
            String promptType,
            Map<String, Object> providerOutput,
            Map<String, Object> diagnostics
    ) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("rawPromptResponse", rawPromptResponse(null, promptType, providerOutput, diagnostics));
        return debug;
    }

    private Map<String, Object> rawPromptFailureOutput(
            RuntimeException ex,
            String promptType,
            Map<String, Object> providerOutput,
            Map<String, Object> diagnostics
    ) {
        if (ex instanceof CreatorAiOutputException aiOutputException && !aiOutputException.getDebugPayload().isEmpty()) {
            return new LinkedHashMap<>(aiOutputException.getDebugPayload());
        }
        return rawPromptDebugPayload(promptType, providerOutput, diagnostics);
    }

    private Map<String, Object> copyDebugMap(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    private Map<String, Object> rawTextJsonMap(Object rawText) {
        String text = stripJsonFence(stringValue(rawText).trim());
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            String objectJson = firstBalancedJsonObject(text);
            if (!objectJson.isBlank()) {
                try {
                    return objectMapper.readValue(objectJson, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                } catch (JsonProcessingException ignored) {
                    return Map.of();
                }
            }
            return Map.of();
        }
    }

    private String firstBalancedJsonObject(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, index + 1).trim();
                }
            }
        }
        return "";
    }

    private List<Object> firstListValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<GeneratedStoryScriptResponse.CharacterProfile> characterProfilesFor(
            CreatorIdea storyIdea,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage
    ) {
        String category = defaultString(categoryCode, "creator").toLowerCase(Locale.ROOT);
        if (category.contains("politic") || category.contains("news")) {
            return List.of(
                    GeneratedStoryScriptResponse.CharacterProfile.builder()
                            .name(isHindiLikeLanguage(dialogueLanguage) ? "Creator" : "Commentator")
                            .role("Neutral creator commentator")
                            .gender("Any")
                            .age("26")
                            .ageRange("22-35")
                            .look("Everyday creator look, expressive face, clean background, phone-friendly framing.")
                            .profile("A neutral short-form creator interpreting the silent public moment without impersonating any politician.")
                            .persona("Observant, restrained, curious, and careful with claims.")
                            .backstory(defaultString(storyIdea.getSummary(), "Notices a silent political reaction and turns it into a non-partisan observation about body language and public perception."))
                            .motivation("Make viewers notice the non-verbal story without adding unsupported claims.")
                            .fearOrBlock("Overexplaining the moment or sounding biased.")
                            .relationshipToStory("Carries the viewer through the observation and keeps the piece grounded.")
                            .speakingStyle(languageInstructionFor(dialogueLanguage) + ", neutral, concise, and non-defamatory")
                            .visualIdentity("Simple creator setup, readable face, neutral clothing, no party colors as costume.")
                            .build(),
                    GeneratedStoryScriptResponse.CharacterProfile.builder()
                            .name(isHindiLikeLanguage(dialogueLanguage) ? "Dost" : "Viewer Friend")
                            .role("Audience proxy")
                            .gender("Any")
                            .age("28")
                            .ageRange("22-40")
                            .look("Casual viewer styling, simple reaction expression, minimal movement.")
                            .profile("Represents the audience asking what the silent reaction could mean.")
                            .persona("Curious but skeptical; asks for context instead of accepting a hot take.")
                            .backstory("Has seen many over-edited political clips and wants a cleaner read of the moment.")
                            .motivation("Push the creator to explain the observation clearly and fairly.")
                            .fearOrBlock("Turning a body-language moment into a false claim.")
                            .relationshipToStory("Creates contrast and keeps the interpretation responsible.")
                            .speakingStyle(languageInstructionFor(dialogueLanguage) + ", conversational and balanced")
                            .visualIdentity("Subtle reaction, side glance, easy to read in a phone frame.")
                            .build()
            );
        }
        String mainName = isHindiLikeLanguage(dialogueLanguage) ? "Priya" : "Asha";
        String friendName = isHindiLikeLanguage(dialogueLanguage) ? "Neha" : "Maya";
        String observerName = category.contains("family") || inferredTone.contains("comedy") ? "Aunty" : "Rohan";
        return List.of(
                GeneratedStoryScriptResponse.CharacterProfile.builder()
                        .name(mainName)
                        .role("Main creator")
                        .gender("Female")
                        .age("26")
                        .ageRange("22-30")
                        .look("Everyday casual outfit, natural face, expressive eyes, subtle reactions, phone-friendly styling.")
                        .profile("Beginner creator and emotional point of view for the short; the viewer should recognize themselves through her hesitation and small win.")
                        .persona("Relatable beginner who wants to improve but still overthinks the first step.")
                        .backstory(defaultString(storyIdea.getSummary(), "She has tried to start before, but inconsistency and self-consciousness made the idea feel bigger than it is."))
                        .motivation("She wants a small win that feels real enough to repeat.")
                        .fearOrBlock("Being judged, failing publicly, or looking like she does not belong yet.")
                        .relationshipToStory("The audience experiences the idea through her decision and reaction.")
                        .speakingStyle(languageInstructionFor(dialogueLanguage) + ", simple, honest, and not overacted")
                        .visualIdentity("Everyday outfit, natural face, phone-friendly styling, expressive but subtle reactions")
                        .build(),
                GeneratedStoryScriptResponse.CharacterProfile.builder()
                        .name(friendName)
                        .role("Support or contrast character")
                        .gender("Female")
                        .age("27")
                        .ageRange("22-32")
                        .look("Simple casual look, relaxed posture, grounded body language, supportive eye contact.")
                        .profile("Practical friend who creates contrast and helps the main creator move without turning the scene into advice.")
                        .persona("Practical friend who gives the main character a tiny push without sounding preachy.")
                        .backstory("Has seen the main character delay this decision before and knows the smallest useful nudge will work better than advice.")
                        .motivation("Wants the main character to take the first step without making the moment dramatic.")
                        .fearOrBlock("Pushing too hard and making the main character withdraw.")
                        .relationshipToStory("Creates contrast and gives the scene a natural second voice.")
                        .speakingStyle(languageInstructionFor(dialogueLanguage) + ", casual and conversational")
                        .visualIdentity("Simple casual look, grounded body language, supportive eye contact")
                        .build(),
                GeneratedStoryScriptResponse.CharacterProfile.builder()
                        .name(observerName)
                        .role("Reaction character")
                        .gender("Aunty".equals(observerName) ? "Female" : "Male")
                        .age("Aunty".equals(observerName) ? "45" : "32")
                        .ageRange("30-55")
                        .look("One clear readable expression, minimal movement, simple everyday styling that does not steal focus.")
                        .profile("Light reaction presence who makes the private decision feel social, funny, or more shareable.")
                        .persona("Adds humor, pressure, or social reality through a quick reaction.")
                        .backstory("Represents the outside world watching the creator's small but meaningful choice.")
                        .motivation("Creates a short-form reaction beat that makes the moment feel alive.")
                        .fearOrBlock("Could pull focus if overplayed, so the reaction must stay small.")
                        .relationshipToStory("Turns the private decision into a shareable moment.")
                        .speakingStyle(languageInstructionFor(dialogueLanguage) + ", short reaction lines only")
                        .visualIdentity("One clear expression, minimal movement, readable in a phone frame")
                        .build()
        );
    }

    private List<GeneratedStoryScriptResponse.StoryBeat> storyBeatsFor(
            int durationSeconds,
            List<GeneratedStoryScriptResponse.CharacterProfile> characters
    ) {
        int setupSeconds = Math.max(3, Math.round(durationSeconds * 0.18f));
        int conflictSeconds = Math.max(5, Math.round(durationSeconds * 0.32f));
        int actionSeconds = Math.max(5, Math.round(durationSeconds * 0.30f));
        int payoffSeconds = Math.max(3, durationSeconds - setupSeconds - conflictSeconds - actionSeconds);
        String main = characters.isEmpty() ? "Main creator" : characters.get(0).getName();
        String support = characters.size() > 1 ? characters.get(1).getName() : "Support character";
        return List.of(
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(1)
                        .title("Decision Point")
                        .summary(main + " is caught right before taking action, and the audience can immediately read the hesitation.")
                        .characterFocus(main)
                        .emotionalPurpose("Create curiosity and recognition in the first seconds.")
                        .estimatedSeconds(setupSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(2)
                        .title("The Block")
                        .summary(main + " reveals the excuse, insecurity, or funny contradiction that makes the idea relatable.")
                        .characterFocus(main)
                        .emotionalPurpose("Make the audience feel seen before the solution appears.")
                        .estimatedSeconds(conflictSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(3)
                        .title("The Small Push")
                        .summary(support + " gives a tiny nudge, and " + main + " chooses the smallest possible action.")
                        .characterFocus(support)
                        .emotionalPurpose("Move from stuck energy to visible progress.")
                        .estimatedSeconds(actionSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(4)
                        .title("Payoff")
                        .summary(main + " reacts to the small win, ending with a line, look, or overlay that viewers can save.")
                        .characterFocus(main)
                        .emotionalPurpose("Deliver confidence, relief, or a light comedic release.")
                        .estimatedSeconds(payoffSeconds)
                        .build()
        );
    }

    private CreatorIdea saveStoryScriptOnIdea(
            CreatorIdea storyIdea,
            GeneratedStoryScriptResponse.StoryScript storyScript,
            String scriptText,
            UUID promptRunId,
            UUID generationJobId,
            int durationSeconds,
            String dialogueLanguage,
            String screenType
    ) {
        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("storyScript", toStoryScriptMap(storyScript));
        context.put("storyScriptGeneratedAt", OffsetDateTime.now().toString());
        context.put("storyScriptPromptRunId", promptRunId == null ? null : promptRunId.toString());
        context.put("storyScriptGenerationJobId", generationJobId == null ? null : generationJobId.toString());
        context.put("storyScriptDurationSeconds", durationSeconds);
        context.put("storyScriptDialogueLanguage", dialogueLanguage);
        context.put("storyScriptScreenType", screenType);

        storyIdea.setTitle(storyScript.getProjectTitle());
        storyIdea.setScript(scriptText);
        storyIdea.setDurationSeconds(durationSeconds);
        storyIdea.setPromptRunId(promptRunId);
        storyIdea.setGenerationJobId(generationJobId);
        storyIdea.setSelectionContext(context);
        storyIdea.setStatus("SCRIPT_GENERATED");
        storyIdea.setSaved(true);
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        return ideaRepository.save(storyIdea);
    }

    private GeneratedStoryScriptResponse toGeneratedStoryScriptResponse(
            CreatorIdea savedIdea,
            UUID lockedIdeaId,
            UUID promptRunId,
            GeneratedStoryScriptResponse.StoryScript storyScript,
            String scriptText,
            Map<String, Object> rawPromptResponse
    ) {
        return GeneratedStoryScriptResponse.builder()
                .ideaId(savedIdea.getId())
                .lockedIdeaId(lockedIdeaId)
                .projectId(savedIdea.getProjectId())
                .promptRunId(promptRunId)
                .title(savedIdea.getTitle())
                .scriptText(scriptText)
                .scriptJson(storyScript)
                .rawPromptResponse(rawPromptResponse)
                .durationSeconds(savedIdea.getDurationSeconds())
                .status(savedIdea.getStatus())
                .generatedAt(savedIdea.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private GeneratedStoryScriptResponse.StoryScript readStoryScriptFromIdea(CreatorIdea storyIdea) {
        Map<String, Object> context = storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext();
        Object payload = context.get("storyScript");
        if (payload instanceof Map<?, ?> map) {
            return objectMapper.convertValue((Map<String, Object>) map, GeneratedStoryScriptResponse.StoryScript.class);
        }
        return null;
    }

    private Map<String, Object> toStoryScriptMap(GeneratedStoryScriptResponse.StoryScript storyScript) {
        return objectMapper.convertValue(storyScript, new TypeReference<Map<String, Object>>() {
        });
    }

    private String buildStoryScriptText(GeneratedStoryScriptResponse.StoryScript storyScript) {
        StringBuilder builder = new StringBuilder();
        builder.append("Title: ").append(defaultString(storyScript.getProjectTitle(), "Creator Story Script")).append("\n");
        builder.append("Duration: ").append(storyScript.getDuration()).append("s\n");
        builder.append("Language: ").append(defaultString(storyScript.getDialogueLanguage(), "English")).append("\n");
        builder.append("Screen: ").append(defaultString(storyScript.getScreenType(), "vertical")).append("\n\n");
        builder.append("Logline:\n").append(defaultString(storyScript.getLogline(), "")).append("\n\n");
        builder.append("Storyline:\n").append(defaultString(storyScript.getStoryline(), "")).append("\n\n");
        builder.append("Central Conflict:\n").append(defaultString(storyScript.getCentralConflict(), "")).append("\n\n");
        builder.append("Characters:\n");
        for (GeneratedStoryScriptResponse.CharacterProfile character : storyScript.getCharacters() == null ? List.<GeneratedStoryScriptResponse.CharacterProfile>of() : storyScript.getCharacters()) {
            builder.append("- ").append(character.getName()).append(" (").append(character.getRole()).append("): ")
                    .append("Gender: ").append(defaultString(character.getGender(), "")).append(". ")
                    .append("Age: ").append(defaultString(character.getAge(), defaultString(character.getAgeRange(), ""))).append(". ")
                    .append("Look: ").append(defaultString(character.getLook(), defaultString(character.getVisualIdentity(), ""))).append(". ")
                    .append("Profile: ").append(defaultString(character.getProfile(), defaultString(character.getPersona(), ""))).append(". ")
                    .append("Persona: ").append(defaultString(character.getPersona(), "")).append(". Backstory: ")
                    .append(defaultString(character.getBackstory(), "")).append("\n");
        }
        builder.append("\nStory Beats:\n");
        for (GeneratedStoryScriptResponse.StoryBeat beat : storyScript.getBeats() == null ? List.<GeneratedStoryScriptResponse.StoryBeat>of() : storyScript.getBeats()) {
            builder.append(beat.getBeatNumber()).append(". ").append(beat.getTitle()).append(" - ")
                    .append(defaultString(beat.getSummary(), "")).append("\n");
        }
        return builder.toString();
    }

    private String languageInstructionFor(String dialogueLanguage) {
        String bucket = languageBucket(dialogueLanguage);
        return switch (bucket) {
            case "hindi" -> "Hinglish/Hindi";
            case "tamil" -> "Tamil";
            case "telugu" -> "Telugu";
            case "bengali" -> "Bengali";
            case "marathi" -> "Marathi";
            default -> "English";
        };
    }

    private boolean isHindiLikeLanguage(String dialogueLanguage) {
        return "hindi".equals(languageBucket(dialogueLanguage));
    }

    private GeneratedScriptResponse.CinematicScript buildCinematicScriptPayload(
            CreatorIdea storyIdea,
            GeneratedStoryScriptResponse.StoryScript storyScript,
            int durationSeconds,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType
    ) {
        int totalShots = shotCountForDuration(durationSeconds);
        List<GeneratedStoryScriptResponse.CharacterProfile> characters =
                storyScript != null && storyScript.getCharacters() != null && !storyScript.getCharacters().isEmpty()
                        ? storyScript.getCharacters()
                        : characterProfilesFor(storyIdea, categoryCode, inferredTone, dialogueLanguage);
        List<GeneratedScriptResponse.CinematicShot> shots = new java.util.ArrayList<>();
        for (int index = 0; index < totalShots; index++) {
            int start = Math.round((durationSeconds * index) / (float) totalShots);
            int end = Math.max(start + 1, Math.round((durationSeconds * (index + 1)) / (float) totalShots));
            if (index == totalShots - 1) {
                end = durationSeconds;
            }
            shots.add(buildCinematicShot(storyIdea, categoryCode, inferredTone, dialogueLanguage, screenType, characters, index + 1, start, end, totalShots));
        }

        return GeneratedScriptResponse.CinematicScript.builder()
                .projectTitle(cleanBaseTitle(defaultString(storyScript == null ? null : storyScript.getProjectTitle(), storyIdea.getTitle())))
                .duration(durationSeconds)
                .totalShots(totalShots)
                .pacingStyle(durationSeconds <= 30
                        ? "Fast hook with practical emotional beats and a clear payoff"
                        : "Retention-led pacing with fast opening cuts, slower emotional pauses, and practical montage beats")
                .emotionalArc(defaultString(storyScript == null ? null : storyScript.getEmotionalArc(), "Immediate tension -> relatable effort -> small visible shift -> satisfying creator payoff"))
                .hookStrategy(defaultString(storyScript == null ? null : storyScript.getHook(), "Start with the most human moment first: hesitation, contradiction, vulnerability, or a visual decision point."))
                .creatorFitReasoning("Screenplay generated from the saved story script, keeping character names, backstories, and motivations consistent.")
                .audienceFitReasoning(defaultString(storyScript == null ? null : storyScript.getCentralConflict(), "Built for short-form viewers who need quick context, visible progress, and a saveable ending."))
                .overallExecutionDifficulty("Beginner Friendly")
                .category(categoryCode)
                .inferredTone(inferredTone)
                .dialogueLanguage(dialogueLanguage)
                .screenType(screenType)
                .shots(shots)
                .build();
    }

    private GeneratedScriptResponse.CinematicShot buildCinematicShot(
            CreatorIdea storyIdea,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType,
            List<GeneratedStoryScriptResponse.CharacterProfile> characters,
            int shotNumber,
            int startSecond,
            int endSecond,
            int totalShots
    ) {
        String title = cleanBaseTitle(storyIdea.getTitle());
        String phase = shotPhase(shotNumber, totalShots);
        String shotType = shotTypeFor(shotNumber);
        String cameraAngle = cameraAngleFor(shotNumber);
        String movement = cameraMovementFor(shotNumber, phase);
        int fps = fpsFor(shotNumber, phase);
        String expression = expressionFor(phase, inferredTone);
        String action = actionFor(phase, title);

        return GeneratedScriptResponse.CinematicShot.builder()
                .shotNumber(shotNumber)
                .startTime(formatTime(startSecond))
                .endTime(formatTime(endSecond))
                .durationSeconds((double) Math.max(1, endSecond - startSecond))
                .title("%02d. %s".formatted(shotNumber, titleForPhase(phase)))
                .purpose(purposeForPhase(phase))
                .shotType(shotType)
                .cameraAngle(cameraAngle)
                .cameraMovement(movement)
                .lensSuggestion("1x phone camera, use 0.5x only for tight rooms")
                .fps(fps)
                .composition(compositionFor(phase, screenType))
                .setDesign(setDesignFor(categoryCode, phase, screenType))
                .peopleInFrame(peopleInFrameFor(phase))
                .primaryActors(primaryActorsFor(phase, dialogueLanguage, characters))
                .sideActors(sideActorsFor(phase, inferredTone, dialogueLanguage, characters))
                .primaryActorAction(primaryActorActionFor(phase, title))
                .sideActorAction(sideActorActionFor(phase, inferredTone))
                .expression(expression)
                .emotion(emotionFor(phase, inferredTone))
                .bodyLanguage(bodyLanguageFor(phase))
                .lighting("Natural window light or soft outdoor shade; avoid harsh overhead light")
                .environment(environmentFor(categoryCode))
                .action(action)
                .voiceOver(voiceOverFor(phase, dialogueLanguage))
                .dialogue(dialogueMapForPhase(phase, dialogueLanguage))
                .textOverlay(textOverlayFor(phase, dialogueLanguage))
                .transition(transitionFor(phase))
                .soundDesign(new ArrayList<Object>(soundDesignFor(phase)))
                .editingNotes(new ArrayList<Object>(editingNotesFor(phase)))
                .retentionGoal(retentionGoalFor(phase))
                .creatorDirection(creatorDirectionFor(phase))
                .subtitlePosition("lower-middle")
                .mobileFocusArea(mobileFocusAreaFor(screenType))
                .safeZoneNotes(safeZoneNotesFor(screenType))
                .executionDifficulty(executionDifficultyFor(phase))
                .cinematicExecution(cinematicExecutionFor(fps, movement, phase))
                .rookieFriendlyGuide(rookieGuideFor(phase, movement, screenType))
                .sketchPrompt(sketchPromptFor(shotType, cameraAngle, expression, categoryCode, action, screenType))
                .build();
    }

    private List<Map<String, Object>> buildScriptScenes(CreatorIdea idea, int durationSeconds) {
        int sceneCount = durationSeconds >= 60 ? 10 : durationSeconds >= 45 ? 8 : 6;
        int segment = Math.max(1, durationSeconds / sceneCount);
        String title = cleanBaseTitle(idea.getTitle());
        String hook = stringValue(idea.getSelectionContext().getOrDefault("angle", "Story idea"));
        List<String> beats = List.of(
                "Open with the most relatable tension from the selected story idea.",
                "Show the creator facing the small decision that starts the story.",
                "Introduce the obstacle, hesitation, or funny mismatch.",
                "Let the creator take one visible action that changes the energy.",
                "Land the emotional or comedic payoff in a close reaction.",
                "End with a clear text line viewers can remember."
        );

        return java.util.stream.IntStream.range(0, sceneCount)
                .mapToObj(index -> {
                    int start = index * segment;
                    int end = index == sceneCount - 1 ? durationSeconds : Math.min(durationSeconds, (index + 1) * segment);
                    String beat = beats.get(Math.min(index, beats.size() - 1));
                    Map<String, Object> scene = new LinkedHashMap<>();
                    scene.put("sceneNumber", index + 1);
                    scene.put("time", "%d-%d sec".formatted(start, end));
                    scene.put("camera", cameraForScene(index));
                    scene.put("visual", "%s Use the story idea \"%s\" as the scene spine.".formatted(beat, title));
                    scene.put("dialogue", dialogueForScene(index, hook));
                    scene.put("screenText", screenTextForScene(index, hook));
                    scene.put("directorNote", directorNoteForScene(index));
                    scene.put("intent", intentForScene(index));
                    return scene;
                })
                .toList();
    }

    private String buildScriptText(CreatorIdea idea, List<GeneratedScriptResponse.CinematicShot> scenes) {
        StringBuilder builder = new StringBuilder();
        builder.append("Title: ").append(idea.getTitle()).append("\n\n");
        builder.append("Summary: ").append(defaultString(idea.getSummary(), "Generated creator script.")).append("\n\n");
        for (GeneratedScriptResponse.CinematicShot scene : scenes == null ? List.<GeneratedScriptResponse.CinematicShot>of() : scenes) {
            builder.append("Scene ").append(scene.getShotNumber())
                    .append(" (").append(scene.getStartTime()).append(" - ").append(scene.getEndTime()).append(")\n");
            builder.append("Visual: ").append(scene.getAction()).append("\n");
            builder.append("Dialogue: ").append(dialogueText(scene.getDialogue())).append("\n");
            builder.append("Screen Text: ").append(scene.getTextOverlay()).append("\n");
            builder.append("Set Design: ").append(defaultString(scene.getSetDesign(), "")).append("\n");
            builder.append("People In Frame: ").append(scene.getPeopleInFrame() == null ? 1 : scene.getPeopleInFrame()).append("\n");
            builder.append("Primary Actors: ").append(listText(scene.getPrimaryActors())).append("\n");
            builder.append("Side Actors: ").append(listText(scene.getSideActors())).append("\n");
            builder.append("Primary Actor Action: ").append(defaultString(scene.getPrimaryActorAction(), "")).append("\n");
            builder.append("Side Actor Action: ").append(defaultString(scene.getSideActorAction(), "")).append("\n");
            builder.append("Director Note: ").append(scene.getCreatorDirection()).append("\n\n");
        }
        return builder.toString();
    }

    private Map<String, Object> toMap(GeneratedScriptResponse.CinematicScript scriptPayload) {
        return objectMapper.convertValue(scriptPayload, new TypeReference<Map<String, Object>>() {
        });
    }

    private void enrichScriptPayloadWithProductionPlanTags(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            List<ShotProductionPlanTagResponse> productionPlanTags
    ) {
        if (scriptPayload == null) {
            return;
        }
        List<Map<String, Object>> tagMaps = productionPlanTagMaps(productionPlanTags, false);
        scriptPayload.putExtra("productionPlanTags", tagMaps);
        scriptPayload.putExtra("productionPlanTagCount", tagMaps.size());

        Map<Integer, ShotProductionPlanTagResponse> planByShotNumber = new LinkedHashMap<>();
        for (ShotProductionPlanTagResponse tag : productionPlanTags == null ? List.<ShotProductionPlanTagResponse>of() : productionPlanTags) {
            if (tag != null && tag.shotNumber() != null) {
                planByShotNumber.put(tag.shotNumber(), tag);
            }
        }

        List<GeneratedScriptResponse.CinematicShot> shots = scriptPayload.getShots();
        if (shots == null) {
            return;
        }
        for (int index = 0; index < shots.size(); index++) {
            GeneratedScriptResponse.CinematicShot shot = shots.get(index);
            if (shot == null) {
                continue;
            }
            int shotNumber = shot.getShotNumber() == null ? index + 1 : shot.getShotNumber();
            ShotProductionPlanTagResponse plan = planByShotNumber.get(shotNumber);
            if (plan == null) {
                continue;
            }
            shot.putExtra("shotPlanId", plan.planId() == null ? "" : plan.planId().toString());
            shot.putExtra("styleKey", plan.styleKey());
            shot.putExtra("storyboardTag", plan.storyboardTag() == null ? Map.of() : plan.storyboardTag());
            shot.putExtra("lightingBuildSheetTag", plan.lightingBuildSheetTag() == null ? Map.of() : plan.lightingBuildSheetTag());
            shot.putExtra("cameraPlanSheetTag", plan.cameraPlanSheetTag() == null ? Map.of() : plan.cameraPlanSheetTag());
            shot.putExtra("productionPromptRunIds", plan.promptRunIds() == null ? Map.of() : plan.promptRunIds());
        }
    }

    private ProductionPlanGenerationResult generateProductionPlanTagsIfPossible(
            CreatorScript script,
            Map<String, Object> scriptPayloadMap,
            List<Map<String, Object>> shotPayloads
    ) {
        if (shotPayloads == null || shotPayloads.isEmpty()) {
            return new ProductionPlanGenerationResult(List.of(), "SKIPPED", "No screenplay shots were available for production plan tags.", Map.of());
        }
        try {
            List<ShotProductionPlanTagResponse> tags =
                    productionPlanTagService.generateTagsForScript(script, scriptPayloadMap, shotPayloads, null);
            return new ProductionPlanGenerationResult(tags, "GENERATED", "", Map.of());
        } catch (RuntimeException ex) {
            String message = generationExceptionMessage(ex);
            Map<String, Object> debug = ex instanceof CreatorAiOutputException aiOutputException
                    ? new LinkedHashMap<>(aiOutputException.getDebugPayload())
                    : Map.of();
            log.warn(
                    "Creator screenplay generated but production plan tags failed scriptId={} storyIdeaId={} reason={}",
                    script == null ? null : script.getId(),
                    script == null ? null : script.getStoryIdeaId(),
                    message,
                    ex
            );
            return new ProductionPlanGenerationResult(List.of(), "FAILED", message, debug);
        }
    }

    private ProductionPlanGenerationResult productionPlanNotStarted() {
        return new ProductionPlanGenerationResult(
                List.of(),
                "NOT_STARTED",
                "Production plan JSON has not been generated yet. Use the shot plan generation step after reviewing the screenplay.",
                Map.of()
        );
    }

    private void putProductionPlanStatus(
            Map<String, Object> scriptPayloadMap,
            ProductionPlanGenerationResult productionPlanResult
    ) {
        if (scriptPayloadMap == null || productionPlanResult == null) {
            return;
        }
        scriptPayloadMap.put("productionPlanStatus", productionPlanResult.status());
        scriptPayloadMap.put("productionPlanTagCount", productionPlanResult.tags().size());
        if (!productionPlanResult.error().isBlank()) {
            scriptPayloadMap.put("productionPlanError", productionPlanResult.error());
        }
        if (!productionPlanResult.debug().isEmpty()) {
            scriptPayloadMap.put("productionPlanDebug", productionPlanResult.debug());
        }
    }

    private String generationExceptionMessage(RuntimeException ex) {
        if (ex instanceof ResponseStatusException responseStatusException) {
            return defaultString(responseStatusException.getReason(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
        }
        return defaultString(ex == null ? null : ex.getMessage(), ex == null ? "Generation failed." : ex.getClass().getSimpleName());
    }

    private void stripEmbeddedProductionPlanTags(GeneratedScriptResponse.CinematicScript scriptPayload) {
        if (scriptPayload == null) {
            return;
        }
        scriptPayload.getExtra().remove("productionPlanTags");
        scriptPayload.getExtra().remove("productionPlanTagCount");
        if (scriptPayload.getShots() == null) {
            return;
        }
        for (GeneratedScriptResponse.CinematicShot shot : scriptPayload.getShots()) {
            if (shot == null) {
                continue;
            }
            shot.getExtra().remove("shotPlanId");
            shot.getExtra().remove("styleKey");
            shot.getExtra().remove("storyboardTag");
            shot.getExtra().remove("lightingBuildSheetTag");
            shot.getExtra().remove("cameraPlanSheetTag");
            shot.getExtra().remove("productionPromptRunIds");
        }
    }

    private void enrichAudioAndMusicDesign(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            String categoryCode,
            String inferredTone
    ) {
        if (scriptPayload == null) {
            return;
        }
        String tone = defaultString(inferredTone, defaultString(scriptPayload.getInferredTone(), "emotionally clear, practical, and creator-friendly"));
        String category = defaultString(categoryCode, defaultString(scriptPayload.getCategory(), "creator"));
        String musicMood = backgroundMusicMood(category, tone);

        Map<String, Object> soundPlan = new LinkedHashMap<>();
        soundPlan.put("mixIntent", "Dialogue and performance stay primary; sound design supports the emotional beat without covering speech.");
        soundPlan.put("ambientBedStrategy", "Every shot carries a low continuous room/location bed so cuts do not feel empty.");
        soundPlan.put("syncHitStrategy", "Each shot has one clear punctuation hit timed to the visual action, text reveal, or dialogue turn.");
        soundPlan.put("dialogueMixNote", "Keep spoken lines forward, clean, and centered; duck music under dialogue.");
        soundPlan.put("deliverables", List.of("ambient_bed", "sync_hit", "background_music_cue", "dialogue_clean_track"));
        scriptPayload.setSoundDesignPlan(nonEmptyMap(scriptPayload.getSoundDesignPlan(), soundPlan));

        Map<String, Object> musicPlan = new LinkedHashMap<>();
        musicPlan.put("musicMood", musicMood);
        musicPlan.put("bpmRange", bpmRangeFor(tone));
        musicPlan.put("instrumentation", instrumentationFor(category, tone));
        musicPlan.put("energyCurve", "hook restraint -> mid-story pulse -> payoff lift -> clean tail for final caption");
        musicPlan.put("duckingRule", "Music drops 6-9 dB under dialogue and rises only during silent action or payoff reaction.");
        musicPlan.put("usageNote", "Use royalty-safe or original music only; avoid recognizable copyrighted tracks.");
        scriptPayload.setBackgroundMusicPlan(nonEmptyMap(scriptPayload.getBackgroundMusicPlan(), musicPlan));

        List<GeneratedScriptResponse.CinematicShot> shots = scriptPayload.getShots();
        if (shots == null || shots.isEmpty()) {
            return;
        }
        int totalShots = scriptPayload.getTotalShots() == null || scriptPayload.getTotalShots() <= 0
                ? shots.size()
                : scriptPayload.getTotalShots();
        for (GeneratedScriptResponse.CinematicShot shot : shots) {
            if (shot == null) {
                continue;
            }
            int shotNumber = shot.getShotNumber() == null ? 1 : shot.getShotNumber();
            String phase = shotPhase(shotNumber, totalShots);
            double start = secondsValue(shot.getStartTime(), Math.max(0, shotNumber - 1));
            double duration = shot.getDurationSeconds() == null || shot.getDurationSeconds() <= 0
                    ? Math.max(1d, secondsValue(shot.getEndTime(), start + 3d) - start)
                    : shot.getDurationSeconds();
            double end = secondsValue(shot.getEndTime(), start + duration);
            String ambient = defaultString(
                    firstSoundLayerDescription(shot.getSoundDesign(), "ambient_bed"),
                    ambientBedFor(category, phase)
            );
            String sync = defaultString(
                    firstSoundLayerDescription(shot.getSoundDesign(), "sync_hit"),
                    syncHitFor(phase, shot)
            );

            shot.setSoundDesign(normalizedSoundDesignLayers(shot.getSoundDesign(), ambient, sync, start, end));
            shot.setAmbientBedDescription(defaultString(shot.getAmbientBedDescription(), ambient));
            shot.setSyncHitDescription(defaultString(shot.getSyncHitDescription(), sync));
            shot.setBackgroundMusicCue(nonEmptyMap(shot.getBackgroundMusicCue(), backgroundMusicCueFor(phase, musicMood, start, end, duration)));
            if (defaultString(shot.getAudioDescription(), "").isBlank()) {
                shot.setAudioDescription("Ambient bed: " + ambient + ". Sync hit: " + sync + ". Music: " + musicMood + ".");
            }
        }
    }

    private Map<String, Object> backgroundMusicCueFor(String phase, String musicMood, double start, double end, double duration) {
        Map<String, Object> cue = new LinkedHashMap<>();
        cue.put("cueType", switch (phase) {
            case "hook" -> "tension_hook";
            case "payoff" -> "payoff_lift";
            case "close" -> "resolve_tail";
            default -> "light_pulse";
        });
        cue.put("musicMood", musicMood);
        cue.put("startTimeSeconds", start);
        cue.put("endTimeSeconds", end);
        cue.put("durationSeconds", duration);
        cue.put("volumeLevel", "low_under_dialogue");
        cue.put("duckUnderDialogue", true);
        cue.put("editNote", "Cut music on the visual transition; do not let the cue fight the spoken line.");
        return cue;
    }

    private List<Object> normalizedSoundDesignLayers(List<Object> existing, String ambient, String sync, double start, double end) {
        List<Object> layers = new ArrayList<>();
        layers.add(soundLayer("ambient_bed", ambient, start, "low"));
        layers.add(soundLayer("sync_hit", sync, Math.max(start, end - 0.35d), "high"));
        for (Object item : existing == null ? List.of() : existing) {
            Map<String, Object> map = mapValue(item);
            String layerType = stringValue(map.get("layerType"));
            if ("ambient_bed".equalsIgnoreCase(layerType) || "sync_hit".equalsIgnoreCase(layerType)) {
                continue;
            }
            if (!map.isEmpty()) {
                layers.add(map);
            }
        }
        return layers;
    }

    private Map<String, Object> soundLayer(String layerType, String description, double timingSeconds, String volumeLevel) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("layerType", layerType);
        layer.put("description", description);
        layer.put("timingSeconds", timingSeconds);
        layer.put("volumeLevel", volumeLevel);
        return layer;
    }

    private String firstSoundLayerDescription(List<Object> soundDesign, String layerType) {
        if (soundDesign == null) {
            return "";
        }
        for (Object item : soundDesign) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty() && layerType.equalsIgnoreCase(stringValue(map.get("layerType")))) {
                return stringValue(map.get("description"));
            }
        }
        return "";
    }

    private String ambientBedFor(String categoryCode, String phase) {
        String category = defaultString(categoryCode, "creator").toLowerCase(Locale.ROOT);
        if (category.contains("food")) {
            return "Kitchen room tone + soft utensil movement + faint appliance hum";
        }
        if (category.contains("fitness")) {
            return "Indoor workout room tone + shoe movement + soft breath texture";
        }
        if (category.contains("politic") || category.contains("news")) {
            return "Neutral room tone + faint phone playback texture kept low";
        }
        if ("hook".equals(phase)) {
            return "Low room ambience with a tiny pre-beat silence";
        }
        return "Clean room tone + subtle natural action sound";
    }

    private String syncHitFor(String phase, GeneratedScriptResponse.CinematicShot shot) {
        String overlay = defaultString(shot == null ? null : shot.getTextOverlay(), "the beat");
        return switch (phase) {
            case "hook" -> "Soft whoosh hit as the hook text appears";
            case "payoff" -> "Light musical lift on " + truncate(overlay, 40);
            case "close" -> "Clean button hit under the final caption";
            default -> "Small tactile hit matched to the main action";
        };
    }

    private String backgroundMusicMood(String categoryCode, String tone) {
        String text = (defaultString(categoryCode, "") + " " + defaultString(tone, "")).toLowerCase(Locale.ROOT);
        if (text.contains("comedy") || text.contains("funny") || text.contains("relatable")) {
            return "light playful pulse with soft comedic timing";
        }
        if (text.contains("romantic") || text.contains("relationship")) {
            return "warm emotional pop bed with gentle lift";
        }
        if (text.contains("fitness") || text.contains("motivation") || text.contains("confidence")) {
            return "clean motivational beat with restrained rise";
        }
        if (text.contains("politic") || text.contains("news")) {
            return "minimal neutral tension bed, documentary-clean";
        }
        return "subtle creator-friendly bed with emotional lift at payoff";
    }

    private String bpmRangeFor(String tone) {
        String text = defaultString(tone, "").toLowerCase(Locale.ROOT);
        if (text.contains("urgent") || text.contains("fitness") || text.contains("motivation")) {
            return "96-118 BPM";
        }
        if (text.contains("emotional") || text.contains("romantic")) {
            return "70-88 BPM";
        }
        return "82-104 BPM";
    }

    private String instrumentationFor(String categoryCode, String tone) {
        String text = (defaultString(categoryCode, "") + " " + defaultString(tone, "")).toLowerCase(Locale.ROOT);
        if (text.contains("comedy")) {
            return "soft pluck, muted percussion, light bass pulse";
        }
        if (text.contains("fitness") || text.contains("motivation")) {
            return "clean kick, light clap, warm synth pulse";
        }
        if (text.contains("politic") || text.contains("news")) {
            return "low pad, subtle tick, restrained documentary pulse";
        }
        return "warm pad, light percussion, simple melodic lift";
    }

    private double secondsValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value).trim();
        if (text.contains(":")) {
            String[] parts = text.split(":");
            try {
                return (Double.parseDouble(parts[0].replaceAll("[^0-9.\\-]", "")) * 60d)
                        + Double.parseDouble(parts[1].replaceAll("[^0-9.\\-]", ""));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        try {
            return text.isBlank() ? fallback : Double.parseDouble(text.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private List<Map<String, Object>> productionPlanTagMaps(List<ShotProductionPlanTagResponse> productionPlanTags) {
        return productionPlanTagMaps(productionPlanTags, true);
    }

    private List<Map<String, Object>> productionPlanTagMaps(List<ShotProductionPlanTagResponse> productionPlanTags, boolean includeRawPromptResponses) {
        List<Map<String, Object>> tagMaps = objectMapper.convertValue(
                productionPlanTags == null ? List.of() : productionPlanTags,
                new TypeReference<List<Map<String, Object>>>() {
                }
        );
        if (includeRawPromptResponses) {
            return tagMaps;
        }
        for (Map<String, Object> tagMap : tagMaps) {
            if (tagMap != null) {
                tagMap.remove("rawPromptResponses");
            }
        }
        return tagMaps;
    }

    private void putStoryStructure(Map<String, Object> scriptPayloadMap, GeneratedStoryScriptResponse.StoryScript storyScript) {
        if (scriptPayloadMap == null || storyScript == null) {
            return;
        }
        scriptPayloadMap.put("storyCharacters", toGenericMapList(storyScript.getCharacters()));
        scriptPayloadMap.put("storyBeats", toGenericMapList(storyScript.getBeats()));
        scriptPayloadMap.put("storyline", storyScript.getStoryline());
        scriptPayloadMap.put("logline", storyScript.getLogline());
        scriptPayloadMap.put("centralConflict", storyScript.getCentralConflict());
        scriptPayloadMap.put("endingPayoff", storyScript.getEndingPayoff());
    }

    private void putScreenplayPlanningContext(
            Map<String, Object> scriptPayloadMap,
            String budgetTier,
            List<Map<String, Object>> characterCastMappings,
            List<Map<String, Object>> availableActors,
            Map<String, Object> audienceDecision,
            Map<String, Object> brandContext,
            Map<String, Object> creatorContext
    ) {
        if (scriptPayloadMap == null) {
            return;
        }
        scriptPayloadMap.put("budgetTier", defaultString(stringValue(scriptPayloadMap.get("budgetTier")), budgetTier));
        scriptPayloadMap.put("characterCastMappings", characterCastMappings == null ? List.of() : characterCastMappings);
        scriptPayloadMap.put("availableActors", availableActors == null ? List.of() : availableActors);
        scriptPayloadMap.put("audienceDecision", audienceDecision == null ? Map.of() : audienceDecision);
        scriptPayloadMap.put("brandContext", brandContext == null ? Map.of() : brandContext);
        scriptPayloadMap.put("creatorContext", creatorContext == null ? Map.of() : creatorContext);
    }

    private List<Map<String, Object>> toMapList(List<GeneratedScriptResponse.CinematicShot> shots) {
        return objectMapper.convertValue(shots == null ? List.of() : shots, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private Map<String, Object> toGenericMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> converted = objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        Object cleaned = cleanPromptValue(converted);
        return cleaned instanceof Map<?, ?> map
                ? map.entrySet().stream()
                .collect(LinkedHashMap::new, (target, entry) -> target.put(String.valueOf(entry.getKey()), entry.getValue()), LinkedHashMap::putAll)
                : Map.of();
    }

    private List<Map<String, Object>> toGenericMapList(Object values) {
        List<Map<String, Object>> converted = objectMapper.convertValue(values == null ? List.of() : values, new TypeReference<List<Map<String, Object>>>() {
        });
        Object cleaned = cleanPromptValue(converted);
        if (!(cleaned instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) item;
                    return map;
                })
                .toList();
    }

    private Object cleanPromptValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            return trimmed.isBlank() ? null : trimmed;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                Object child = cleanPromptValue(item);
                if (child != null) {
                    cleaned.put(String.valueOf(key), child);
                }
            });
            return cleaned.isEmpty() ? null : cleaned;
        }
        if (value instanceof List<?> list) {
            List<Object> cleaned = list.stream()
                    .map(this::cleanPromptValue)
                    .filter(item -> item != null)
                    .toList();
            return cleaned.isEmpty() ? null : cleaned;
        }
        return value;
    }

    private List<Map<String, Object>> nonEmptyList(List<Map<String, Object>> primary, List<Map<String, Object>> fallback) {
        return primary == null || primary.isEmpty() ? fallback == null ? List.of() : fallback : primary;
    }

    private Map<String, Object> nonEmptyMap(Map<String, Object> primary, Map<String, Object> fallback) {
        return primary == null || primary.isEmpty() ? fallback == null ? Map.of() : fallback : primary;
    }

    private List<Map<String, Object>> promptCharacterCastMappings(CreatorIdea storyIdea, UUID lockedIdeaId, UUID storyIdeaId) {
        List<CreatorCharacterCastMapping> mappings = characterCastMappingRepository
                .findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdOrderByCreatedAtAsc(
                        storyIdea.getTenantId(),
                        storyIdea.getUserId(),
                        lockedIdeaId,
                        storyIdeaId
                );
        return mappings.stream().map(this::mappingPromptMap).toList();
    }

    private Map<String, Object> mappingPromptMap(CreatorCharacterCastMapping mapping) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("characterKey", mapping.getCharacterKey());
        data.put("scriptCharacterId", mapping.getScriptCharacterId());
        data.put("characterName", mapping.getCharacterName());
        data.put("characterRole", mapping.getCharacterRole());
        data.put("castProfileId", mapping.getCastProfileId());
        data.put("actorName", mapping.getCastDisplayName());
        data.put("castPayload", mapping.getCastPayload());
        return data;
    }

    private List<Map<String, Object>> promptAvailableActors(CreatorIdea storyIdea) {
        List<CreatorProfile> profiles = profileRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(storyIdea.getTenantId(), storyIdea.getUserId());
        return profiles.stream().map(this::profilePromptMap).toList();
    }

    private Map<String, Object> profilePromptMap(CreatorProfile profile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", profile.getId());
        data.put("displayName", profile.getDisplayName());
        data.put("roleInShort", profile.getRoleInShort());
        data.put("confirmed", profile.isConfirmed());
        data.put("attributes", profile.getAttributes());
        return data;
    }

    private String dialogueText(Map<String, Object> dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return "";
        }
        return dialogue.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + dialogueValueText(entry.getValue()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String dialogueValueText(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::dialogueValueText).filter(item -> !item.isBlank()).reduce((left, right) -> left + " / " + right).orElse("");
        }
        if (value instanceof Map<?, ?> map && map.get("line") != null) {
            return String.valueOf(map.get("line"));
        }
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> dialogueMapForPhase(String phase, String dialogueLanguage) {
        Map<String, Object> dialogue = new LinkedHashMap<>();
        dialogueForPhase(phase, dialogueLanguage).forEach(dialogue::put);
        return dialogue;
    }

    private String listText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private void linkProjectSelectedIdea(CreatorIdea idea) {
        if (idea.getProjectId() == null) {
            return;
        }
        jdbcTemplate.update(
                """
                update creator_projects
                   set selected_idea_id = ?,
                       status = coalesce(?, status),
                       updated_at = now()
                 where id = ?
                   and tenant_id = ?
                   and user_id = ?
                """,
                idea.getId(),
                idea.getStatus(),
                idea.getProjectId(),
                idea.getTenantId(),
                idea.getUserId()
        );
    }

    private CreatorIdea ensureProjectOnLockedIdea(CreatorIdea lockedIdea) {
        if (lockedIdea == null || lockedIdea.getProjectId() != null) {
            return lockedIdea;
        }
        Map<String, Object> context = lockedIdea.getSelectionContext() == null ? Map.of() : lockedIdea.getSelectionContext();
        CreatorProject project = projectService.ensureProjectForLockedIdea(
                null,
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getTitle(),
                lockedIdea.getSummary(),
                lockedIdea.getSource(),
                lockedIdea.getTrendId(),
                stringValue(context.get("platformCode"), null),
                stringValue(context.get("categoryCode"), null),
                stringValue(context.get("countryCode"), null),
                stringValue(context.get("timeframe"), null),
                lockedIdea.getDurationSeconds(),
                context
        );
        lockedIdea.setProjectId(project.getId());
        lockedIdea.setUpdatedAt(OffsetDateTime.now());
        return ideaRepository.save(lockedIdea);
    }

    private CreatorIdea ensureProjectOnStoryIdea(CreatorIdea lockedIdea, CreatorIdea storyIdea) {
        if (storyIdea == null || storyIdea.getProjectId() != null) {
            return storyIdea;
        }
        CreatorIdea projectLockedIdea = ensureProjectOnLockedIdea(lockedIdea);
        storyIdea.setProjectId(projectLockedIdea.getProjectId());
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        return ideaRepository.save(storyIdea);
    }

    private Pageable normalizePageable(Pageable pageable) {
        int page = pageable == null ? 0 : Math.max(0, pageable.getPageNumber());
        int size = pageable == null ? DEFAULT_PAGE_SIZE : pageable.getPageSize();
        size = Math.max(1, Math.min(10, size));
        return PageRequest.of(page, size);
    }

    @SuppressWarnings("unchecked")
    private GeneratedIdeaResponse toResponse(CreatorIdea idea) {
        Map<String, Object> context = idea.getSelectionContext() == null ? Map.of() : idea.getSelectionContext();
        List<String> hashtags = context.get("hashtags") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("#CreatorIdea", "#Shorts");
        Map<String, Object> creativeNotes = context.get("creativeNotes") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return new GeneratedIdeaResponse(
                idea.getId(),
                UUID.fromString(String.valueOf(context.get("parentLockedIdeaId"))),
                idea.getProjectId(),
                idea.getTitle(),
                idea.getSummary(),
                idea.getSource(),
                idea.getDurationSeconds(),
                hashtags,
                creativeNotes,
                idea.getCreatedAt()
        );
    }

    private List<String> hashtagsFor(CreatorIdea lockedIdea, String angle) {
        String source = defaultString(lockedIdea.getSource(), "idea").replaceAll("[^A-Za-z0-9]", "");
        String angleTag = angle.replaceAll("[^A-Za-z0-9]", "");
        return List.of("#" + source, "#" + angleTag, "#ShortsIdea");
    }

    private String targetEmotionFor(String angle) {
        String normalized = angle.toLowerCase(Locale.ROOT);
        if (normalized.contains("comedy") || normalized.contains("punchline")) {
            return "Relatable humor";
        }
        if (normalized.contains("confession") || normalized.contains("emotional")) {
            return "Honest connection";
        }
        if (normalized.contains("tutorial") || normalized.contains("checklist")) {
            return "Clarity and usefulness";
        }
        return "Fast curiosity";
    }

    private String storyShapeFor(String angle) {
        return switch (angle.toLowerCase(Locale.ROOT)) {
            case "countdown challenge" -> "3 fast beats ending in a clear challenge CTA";
            case "two character conflict" -> "Setup, tension, reaction, payoff";
            case "mini tutorial" -> "Problem, steps, result";
            case "day one diary" -> "Nervous start, honest obstacle, small win";
            default -> "Hook, escalation, payoff";
        };
    }

    private int normalizeDuration(Integer requestedDuration, Integer ideaDuration) {
        int duration = requestedDuration == null ? (ideaDuration == null ? 30 : ideaDuration) : requestedDuration;
        if (duration < 15) {
            return 15;
        }
        if (duration <= 30) {
            return 30;
        }
        if (duration <= 45) {
            return 45;
        }
        if (duration <= 60) {
            return 60;
        }
        return Math.min(duration, 10800);
    }

    private String normalizeDialogueLanguage(String requestedLanguage) {
        String language = defaultString(requestedLanguage, "English").trim();
        return truncate(language, 64);
    }

    private String normalizeScreenType(String requestedScreenType) {
        String screenType = defaultString(requestedScreenType, "vertical").trim().toLowerCase(Locale.ROOT);
        if (screenType.contains("horizontal") || screenType.contains("landscape") || screenType.contains("16:9")) {
            return "horizontal";
        }
        if (screenType.contains("square") || screenType.contains("1:1")) {
            return "square";
        }
        if (screenType.contains("cinemascope") || screenType.contains("2.39")) {
            return "cinemascope";
        }
        return "vertical";
    }

    private String formatTierFor(int durationSeconds) {
        if (durationSeconds <= 20) {
            return "micro_short";
        }
        if (durationSeconds <= 60) {
            return "short_form";
        }
        if (durationSeconds <= 180) {
            return "medium_form";
        }
        if (durationSeconds <= 600) {
            return "long_short";
        }
        if (durationSeconds <= 1800) {
            return "episodic";
        }
        return "feature_film";
    }

    private String actStructureFor(String formatTier) {
        return switch (defaultString(formatTier, "short_form")) {
            case "micro_short", "short_form" -> "single_punch";
            case "medium_form" -> "two_act";
            case "long_short" -> "three_act_compressed";
            case "episodic" -> "three_act_full";
            case "feature_film" -> "save_the_cat";
            default -> "single_punch";
        };
    }

    private String inferBudgetTier(int durationSeconds) {
        if (durationSeconds <= 60) {
            return "zero_budget";
        }
        if (durationSeconds <= 180) {
            return "micro_budget";
        }
        if (durationSeconds <= 600) {
            return "indie";
        }
        if (durationSeconds <= 1800) {
            return "mid_budget";
        }
        return "studio";
    }

    private String inferCategory(CreatorIdea idea) {
        Map<String, Object> context = idea.getSelectionContext() == null ? Map.of() : idea.getSelectionContext();
        Object sourceBrief = context.get("sourceBrief");
        if (sourceBrief instanceof Map<?, ?> sourceMap && sourceMap.get("categoryCode") != null) {
            return String.valueOf(sourceMap.get("categoryCode"));
        }
        Object category = context.get("categoryCode");
        return category == null ? "creator" : String.valueOf(category);
    }

    private String resolveStoryScriptCategory(String requestedCategory, CreatorIdea storyIdea, String ideaText) {
        String currentCategory = defaultString(requestedCategory, inferCategory(storyIdea));
        if (isAiInferCategory(currentCategory)) {
            return "AI_INFER_FROM_IDEA";
        }
        return defaultString(currentCategory, "creator");
    }

    private String resolveScreenplayCategory(
            String requestedCategory,
            CreatorIdea storyIdea,
            GeneratedStoryScriptResponse.StoryScript storyScript,
            String ideaText
    ) {
        String currentCategory = defaultString(requestedCategory, defaultString(storyScript == null ? null : storyScript.getCategory(), inferCategory(storyIdea)));
        if (isAiInferCategory(currentCategory)) {
            return defaultString(storyScript == null ? null : storyScript.getCategory(), "AI_INFER_FROM_IDEA");
        }
        return defaultString(currentCategory, "creator");
    }

    private boolean isAiInferCategory(String category) {
        String normalized = defaultString(category, "").trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.equals("auto")
                || normalized.equals("ai_infer")
                || normalized.equals("ai_infer_from_idea")
                || normalized.equals("infer")
                || normalized.equals("dynamic")
                || normalized.equals("creator");
    }

    private String inferTone(String ideaText, String categoryCode) {
        if (isAiInferCategory(categoryCode)) {
            return "Infer the best tone dynamically from the written idea, including blended humor, emotion, commentary, discipline, or aspiration when the topic mixes worlds.";
        }
        String text = (defaultString(ideaText, "") + " " + defaultString(categoryCode, "")).toLowerCase(Locale.ROOT);
        if (text.matches(".*(modi|rahul|bjp|congress|election|parliament|politic|minister|rally|campaign).*")) {
            return "neutral political commentary with restrained satire and responsible non-verbal analysis";
        }
        if (text.matches(".*(funny|comedy|pov|saas|bahu|joke|relatable).*")) {
            return "relatable comedy with natural reactions";
        }
        if (text.matches(".*(pati|patni|husband|wife|couple|marriage|relationship).*")) {
            return "relatable relationship comedy with natural reactions";
        }
        if (text.matches(".*(transformation|confidence|gym|fitness|self|motivation).*")) {
            return "motivational but grounded and realistic";
        }
        if (text.matches(".*(study|exam|focus|student).*")) {
            return "calm focused progress";
        }
        if (text.matches(".*(beauty|skin|fashion|style).*")) {
            return "clean aspirational but beginner-friendly";
        }
        return "emotionally clear, practical, and creator-friendly";
    }

    private Map<String, Object> toPromptIdeaMap(CreatorIdea idea) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", idea.getId());
        map.put("title", idea.getTitle());
        map.put("summary", idea.getSummary());
        map.put("durationSeconds", idea.getDurationSeconds());
        map.put("source", idea.getSource());
        map.put("selectionContext", idea.getSelectionContext());
        return map;
    }

    private int shotCountForDuration(int durationSeconds) {
        if (durationSeconds <= 15) {
            return 8;
        }
        if (durationSeconds <= 30) {
            return 12;
        }
        if (durationSeconds <= 45) {
            return 18;
        }
        return 24;
    }

    private String shotPhase(int shotNumber, int totalShots) {
        double progress = shotNumber / (double) totalShots;
        if (progress <= 0.2d) {
            return "hook";
        }
        if (progress <= 0.62d) {
            return "progress";
        }
        if (progress <= 0.86d) {
            return "payoff";
        }
        return "close";
    }

    private String titleForPhase(String phase) {
        return switch (phase) {
            case "hook" -> "Hook tension";
            case "progress" -> "Visible effort";
            case "payoff" -> "Emotional turn";
            default -> "Saveable close";
        };
    }

    private String purposeForPhase(String phase) {
        return switch (phase) {
            case "hook" -> "Create immediate curiosity and emotional tension in the first seconds.";
            case "progress" -> "Show practical movement so viewers understand the creator's effort.";
            case "payoff" -> "Land the feeling change or comedic release.";
            default -> "Close with a clear takeaway viewers can remember or save.";
        };
    }

    private String shotTypeFor(int shotNumber) {
        return switch (shotNumber % 5) {
            case 1 -> "Close Up";
            case 2 -> "Detail Shot";
            case 3 -> "Medium Shot";
            case 4 -> "Wide Shot";
            default -> "Reaction Shot";
        };
    }

    private String cameraAngleFor(int shotNumber) {
        return switch (shotNumber % 6) {
            case 1 -> "Front face-level angle";
            case 2 -> "Slight side angle";
            case 3 -> "Hands/object close angle";
            case 4 -> "Over-shoulder angle";
            case 5 -> "Mirror or doorway angle";
            default -> "Centered vertical frame";
        };
    }

    private String cameraMovementFor(int shotNumber, String phase) {
        if ("hook".equals(phase)) {
            return shotNumber % 2 == 0 ? "Static hold" : "Tiny push in";
        }
        if ("progress".equals(phase)) {
            return shotNumber % 3 == 0 ? "Handheld light follow" : "Static with body movement";
        }
        if ("payoff".equals(phase)) {
            return "Slow push in";
        }
        return "Static end frame";
    }

    private int fpsFor(int shotNumber, String phase) {
        if ("payoff".equals(phase) && shotNumber % 2 == 0) {
            return 60;
        }
        return 30;
    }

    private String expressionFor(String phase, String tone) {
        if ("hook".equals(phase)) {
            return tone.contains("comedy") ? "confused, caught-off-guard reaction" : "nervous, uncertain, honest expression";
        }
        if ("payoff".equals(phase)) {
            return tone.contains("comedy") ? "subtle amused realization" : "small relieved smile";
        }
        return "focused but natural expression";
    }

    private String emotionFor(String phase, String tone) {
        if ("hook".equals(phase)) {
            return tone.contains("comedy") ? "relatable awkwardness" : "vulnerability and curiosity";
        }
        if ("progress".equals(phase)) {
            return "effort and realism";
        }
        if ("payoff".equals(phase)) {
            return tone.contains("comedy") ? "light humor payoff" : "confidence shift";
        }
        return "closure and usefulness";
    }

    private String bodyLanguageFor(String phase) {
        return switch (phase) {
            case "hook" -> "Slight pause, shoulders held, hands unsure or frozen mid-action";
            case "progress" -> "Small practical movement, realistic effort, no overacting";
            case "payoff" -> "Relaxed shoulders, softer face, tiny confidence shift";
            default -> "Calm posture, face/action centered for final takeaway";
        };
    }

    private String compositionFor(String phase, String screenType) {
        String frame = "horizontal".equals(screenType)
                ? "Use a wider 16:9 frame with the subject in the center third and enough side context."
                : "Use a vertical 9:16 frame with face/action centered for phone viewing.";
        return switch (phase) {
            case "hook" -> frame + " Keep face or decision object large with minimal background clutter.";
            case "progress" -> frame + " Keep the action readable quickly.";
            case "payoff" -> frame + " Bring face and hands into the safe area for emotional clarity.";
            default -> frame + " Leave negative space for final text overlay.";
        };
    }

    private String environmentFor(String categoryCode) {
        String category = defaultString(categoryCode, "creator").toLowerCase(Locale.ROOT);
        if ((category.contains("politic") || category.contains("news")) && category.contains("fitness")) {
            return "A practical setting that visually bridges public commentary with fitness, discipline, image, or physical effort";
        }
        if (category.contains("relationship") && category.contains("fitness")) {
            return "A practical setting that visually bridges relationship comedy with fitness, routine, movement, or shared discipline";
        }
        if ((category.contains("politic") || category.contains("news")) && category.contains("food")) {
            return "A practical setting that visually bridges public commentary with food, taste, budget, or everyday public reaction";
        }
        if (category.contains("relationship") && category.contains("food")) {
            return "Home kitchen, dinner table, or street-food counter with domestic comedy staging";
        }
        if (category.contains("politic") || category.contains("news")) {
            return "Neutral creator commentary setup, simple room, desk, or phone reaction frame with a public-clip reference";
        }
        if (category.contains("relationship")) {
            return "Real home setting, living room, kitchen doorway, or everyday couple conversation space";
        }
        if (category.contains("fitness")) {
            return "Simple gym corner, home workout space, or outdoor walking area";
        }
        if (category.contains("beauty") || category.contains("fashion")) {
            return "Clean mirror area, bedroom corner, or natural-light window setup";
        }
        if (category.contains("food")) {
            return "Home kitchen counter with simple natural light";
        }
        if (category.contains("study")) {
            return "Desk, notebook, laptop, or quiet study corner";
        }
        return "Real home or everyday location with uncluttered background";
    }

    private String setDesignFor(String categoryCode, String phase, String screenType) {
        String environment = environmentFor(categoryCode);
        String frame = "horizontal".equals(screenType) ? "16:9 frame" : "9:16 phone frame";
        return switch (phase) {
            case "hook" -> environment + ". Keep only one meaningful prop visible, clear background clutter, and leave readable space in the " + frame + ".";
            case "progress" -> environment + ". Arrange props so the action path is obvious and nothing blocks hands, face, or the main object.";
            case "payoff" -> environment + ". Keep the background calm, place the main actor slightly forward, and make the reaction easy to read.";
            default -> environment + ". Leave clean negative space for final overlay and keep platform-safe margins.";
        };
    }

    private int peopleInFrameFor(String phase) {
        return switch (phase) {
            case "hook", "payoff", "close" -> 1;
            default -> 2;
        };
    }

    private List<String> primaryActorsFor(
            String phase,
            String dialogueLanguage,
            List<GeneratedStoryScriptResponse.CharacterProfile> characters
    ) {
        if (characters != null && !characters.isEmpty()) {
            return List.of(characterCastingLabel(characters.get(0)));
        }
        String main = isHindiLikeLanguage(dialogueLanguage) ? "Priya" : "Asha";
        return List.of(main + " - main creator");
    }

    private List<String> sideActorsFor(
            String phase,
            String inferredTone,
            String dialogueLanguage,
            List<GeneratedStoryScriptResponse.CharacterProfile> characters
    ) {
        if ("progress".equals(phase)) {
            if (characters != null && characters.size() > 1) {
                return List.of(characterCastingLabel(characters.get(1)));
            }
            String friend = isHindiLikeLanguage(dialogueLanguage) ? "Neha" : "Maya";
            return List.of(friend + " - support friend");
        }
        if (inferredTone.contains("comedy") && ("hook".equals(phase) || "payoff".equals(phase))) {
            if (characters != null && characters.size() > 2) {
                return List.of(characterCastingLabel(characters.get(2)));
            }
            return List.of("Reaction person - quick comic/social reaction");
        }
        return List.of();
    }

    private String characterCastingLabel(GeneratedStoryScriptResponse.CharacterProfile character) {
        String name = defaultString(character.getName(), "Character");
        String role = defaultString(character.getRole(), "story character");
        String gender = defaultString(character.getGender(), "");
        String age = defaultString(character.getAge(), defaultString(character.getAgeRange(), ""));
        String look = truncate(defaultString(character.getLook(), defaultString(character.getVisualIdentity(), "")), 90);
        String profile = truncate(defaultString(character.getProfile(), defaultString(character.getPersona(), "")), 90);
        List<String> details = new java.util.ArrayList<>();
        if (!gender.isBlank()) {
            details.add(gender);
        }
        if (!age.isBlank()) {
            details.add("age " + age);
        }
        if (!look.isBlank()) {
            details.add("look: " + look);
        }
        if (!profile.isBlank()) {
            details.add("profile: " + profile);
        }
        return details.isEmpty() ? name + " - " + role : name + " - " + role + " (" + String.join("; ", details) + ")";
    }

    private String primaryActorActionFor(String phase, String title) {
        return switch (phase) {
            case "hook" -> "Main actor pauses at the decision point for \"" + title + "\" and lets the hesitation show through face and hands.";
            case "progress" -> "Main actor performs the smallest visible action that moves the story forward.";
            case "payoff" -> "Main actor reacts honestly to the small win or punchline without overacting.";
            default -> "Main actor holds a calm final pose while the final message stays readable.";
        };
    }

    private String sideActorActionFor(String phase, String inferredTone) {
        if ("progress".equals(phase)) {
            return "Side actor gives one small nudge, watches the main actor act, then stays out of the frame's emotional center.";
        }
        if (inferredTone.contains("comedy") && "payoff".equals(phase)) {
            return "Side actor gives a short readable reaction that supports the joke without stealing focus.";
        }
        return "No side actor required; keep background people out of frame unless they support the story beat.";
    }

    private String actionFor(String phase, String title) {
        return switch (phase) {
            case "hook" -> "Creator pauses at the exact moment the story could go either way for \"" + title + "\".";
            case "progress" -> "Creator performs one small realistic action that proves the idea is moving forward.";
            case "payoff" -> "Creator reacts to the small win or punchline with a grounded expression.";
            default -> "Creator ends on a simple repeatable action while final text stays readable.";
        };
    }

    private String voiceOverFor(String phase, String dialogueLanguage) {
        return switch (languageBucket(dialogueLanguage)) {
            case "hindi" -> switch (phase) {
                case "hook" -> "Main yahin rukne wala tha.";
                case "progress" -> "Isliye maine bas ek chhota step liya.";
                case "payoff" -> "Us ek chhoti cheez ne pura mood badal diya.";
                default -> "Agar start karna mushkil lag raha hai, isse save kar lo.";
            };
            case "tamil" -> switch (phase) {
                case "hook" -> "Naan inga dhaan niruthaporennu nenachen.";
                case "progress" -> "Adhanala oru chinna step mattum eduthen.";
                case "payoff" -> "Andha chinna change mood-a maathiduchu.";
                default -> "Start panna kashtama irundha, idha save pannunga.";
            };
            case "telugu" -> switch (phase) {
                case "hook" -> "Nenu ikkade aagipothanu anukunna.";
                case "progress" -> "Anduke oka chinna step teesukunna.";
                case "payoff" -> "Aa chinna change motham feeling marchindi.";
                default -> "Start cheyyadam kashtam anipiste, idi save chesuko.";
            };
            case "bengali" -> switch (phase) {
                case "hook" -> "Ami ekhanei theme jete chhilam.";
                case "progress" -> "Tai ami sudhu ekta chhoto step nilam.";
                case "payoff" -> "Oi chhoto change puro feeling bodle dilo.";
                default -> "Start korte kothin lagle eta save kore rakho.";
            };
            case "marathi" -> switch (phase) {
                case "hook" -> "Mi ithech thambnar hoto.";
                case "progress" -> "Mhanun mi fakt ek chhota step ghetla.";
                case "payoff" -> "Tya chhotya goshtine purna mood badalla.";
                default -> "Start karayla kathin vatat asel tar he save kara.";
            };
            default -> switch (phase) {
                case "hook" -> "I almost stopped right here.";
                case "progress" -> "So I made the next step smaller.";
                case "payoff" -> "That one small move changed the whole feeling.";
                default -> "Save this if you need a simple way to start.";
            };
        };
    }

    private Map<String, String> dialogueForPhase(String phase, String dialogueLanguage) {
        return Map.of("creator", switch (languageBucket(dialogueLanguage)) {
            case "hindi" -> switch (phase) {
                case "hook" -> "Ruko... yahi woh moment hai.";
                case "progress" -> "Theek hai, bas ek chhota step.";
                case "payoff" -> "Arey, yeh sach mein kaam kar gaya.";
                default -> "Chhota start karo. Real rakho.";
            };
            case "tamil" -> switch (phase) {
                case "hook" -> "Nillu... idhu dhaan andha moment.";
                case "progress" -> "Seri, oru chinna step mattum.";
                case "payoff" -> "Idhu nijamave work aayiduchu.";
                default -> "Chinna start pannunga. Real-a irunga.";
            };
            case "telugu" -> switch (phase) {
                case "hook" -> "Aagu... ide aa moment.";
                case "progress" -> "Sare, oka chinna step matrame.";
                case "payoff" -> "Idi nijanga work ayyindi.";
                default -> "Chinnaga start cheyyi. Real ga undu.";
            };
            case "bengali" -> switch (phase) {
                case "hook" -> "Darao... etai oi moment.";
                case "progress" -> "Thik ache, sudhu ekta chhoto step.";
                case "payoff" -> "Eta sotti kaj koreche.";
                default -> "Chhoto kore start koro. Real thako.";
            };
            case "marathi" -> switch (phase) {
                case "hook" -> "Thamba... hach toh moment aahe.";
                case "progress" -> "Chal, fakt ek chhota step.";
                case "payoff" -> "He kharach kaam zala.";
                default -> "Chhota start kara. Real theva.";
            };
            default -> switch (phase) {
                case "hook" -> "Wait... this is the moment.";
                case "progress" -> "Okay, just one small step.";
                case "payoff" -> "That actually worked.";
                default -> "Start small. Keep it real.";
            };
        });
    }

    private String textOverlayFor(String phase, String dialogueLanguage) {
        return switch (languageBucket(dialogueLanguage)) {
            case "hindi" -> switch (phase) {
                case "hook" -> "JAB MAIN RUKNE WALA THA";
                case "progress" -> "EK CHHOTA STEP";
                case "payoff" -> "MOOD BADAL GAYA";
                default -> "IS IDEA KO SAVE KARO";
            };
            case "tamil" -> switch (phase) {
                case "hook" -> "NIRUTHA PORA MOMENT";
                case "progress" -> "ORU CHINNA STEP";
                case "payoff" -> "MOOD MAARIDUCHU";
                default -> "IDHA SAVE PANNUNGA";
            };
            case "telugu" -> switch (phase) {
                case "hook" -> "AAGIPOYE MOMENT";
                case "progress" -> "OKA CHINNA STEP";
                case "payoff" -> "FEELING MARINDI";
                default -> "IDI SAVE CHESUKO";
            };
            case "bengali" -> switch (phase) {
                case "hook" -> "THEME JAWAR MOMENT";
                case "progress" -> "EKTA CHHOTO STEP";
                case "payoff" -> "FEELING BODLE GELO";
                default -> "ETA SAVE KORO";
            };
            case "marathi" -> switch (phase) {
                case "hook" -> "THAMBAYCHA MOMENT";
                case "progress" -> "EK CHHOTA STEP";
                case "payoff" -> "MOOD BADALLA";
                default -> "HE IDEA SAVE KARA";
            };
            default -> switch (phase) {
                case "hook" -> "THE MOMENT I ALMOST STOPPED";
                case "progress" -> "ONE SMALL STEP";
                case "payoff" -> "THIS CHANGED THE FEELING";
                default -> "SAVE THIS IDEA";
            };
        };
    }

    private String transitionFor(String phase) {
        return switch (phase) {
            case "hook" -> "Hard Cut";
            case "progress" -> "Action Cut";
            case "payoff" -> "Soft Beat Cut";
            default -> "Freeze Frame or clean end cut";
        };
    }

    private List<String> soundDesignFor(String phase) {
        if ("hook".equals(phase)) {
            return List.of("Low room ambience", "Tiny pause before beat starts");
        }
        if ("payoff".equals(phase)) {
            return List.of("Music lift", "Soft reaction beat");
        }
        return List.of("Light background music", "Natural action sound");
    }

    private List<String> editingNotesFor(String phase) {
        if ("hook".equals(phase)) {
            return List.of("Cut in quickly; do not use a long intro.", "Keep text readable within the first second.");
        }
        if ("progress".equals(phase)) {
            return List.of("Cut on hand or body movement.", "Keep each shot short enough for retention.");
        }
        if ("payoff".equals(phase)) {
            return List.of("Hold reaction half a beat longer.", "Let the viewer feel the shift before cutting.");
        }
        return List.of("End with readable text.", "Avoid placing text under platform buttons.");
    }

    private String retentionGoalFor(String phase) {
        return switch (phase) {
            case "hook" -> "Stop scrolling by showing uncertainty before explanation.";
            case "progress" -> "Maintain attention through visible action and relatable effort.";
            case "payoff" -> "Reward the viewer with a feeling shift or punchline.";
            default -> "Create a save/share reason with a simple closing thought.";
        };
    }

    private String creatorDirectionFor(String phase) {
        return switch (phase) {
            case "hook" -> "Do less than you think. A real pause is more believable than dramatic acting.";
            case "progress" -> "Move naturally. If it feels slightly imperfect, keep it.";
            case "payoff" -> "Let the reaction be small and honest.";
            default -> "Stay centered, breathe, and let the final text do the work.";
        };
    }

    private GeneratedScriptResponse.ExecutionDifficulty executionDifficultyFor(String phase) {
        return GeneratedScriptResponse.ExecutionDifficulty.builder()
                .score("progress".equals(phase) ? 2 : 1)
                .level("progress".equals(phase) ? "Easy" : "Beginner")
                .requiresTripod("hook".equals(phase) || "close".equals(phase))
                .requiresHelper(false)
                .phoneFriendly(true)
                .build();
    }

    private GeneratedScriptResponse.CinematicExecution cinematicExecutionFor(int fps, String movement, String phase) {
        return GeneratedScriptResponse.CinematicExecution.builder()
                .recommendedFPS(fps)
                .captureMode(fps >= 60 ? "slow_motion_optional" : "normal")
                .playbackSpeed(fps >= 60 ? "0.7x optional" : "1x")
                .cameraStyle(movement.toLowerCase(Locale.ROOT).replace(" ", "_"))
                .stabilization(movement.contains("Handheld") ? "hold phone with two hands" : "static or supported")
                .transitionStyle(transitionFor(phase).toLowerCase(Locale.ROOT).replace(" ", "_"))
                .zoomRecommendation(movement.contains("push") || movement.contains("Push") ? "move phone slowly closer, avoid digital zoom" : "no zoom needed")
                .motionIntensity(movement.contains("Handheld") ? "low" : "minimal")
                .editingComplexity(fps >= 60 ? "easy-medium" : "easy")
                .build();
    }

    private String mobileFocusAreaFor(String screenType) {
        return "horizontal".equals(screenType)
                ? "face, hands, and side context inside the 16:9 center safe area"
                : "face and hands inside the 9:16 center safe area";
    }

    private String safeZoneNotesFor(String screenType) {
        if ("horizontal".equals(screenType)) {
            return "Keep subject and captions inside the center third so the video can still crop safely for social previews.";
        }
        return "Keep important face/action in center. Keep subtitles above bottom platform buttons.";
    }

    private GeneratedScriptResponse.RookieFriendlyGuide rookieGuideFor(String phase, String movement, String screenType) {
        return GeneratedScriptResponse.RookieFriendlyGuide.builder()
                .whatIsThis("A phone-friendly " + phase + " shot that tells one clear part of the story.")
                .whyThisWorks(retentionGoalFor(phase))
                .howToShoot(List.of(
                        "Clean the lens before recording.",
                        "horizontal".equals(screenType) ? "Use horizontal video and keep the subject in the center third." : "Use vertical video.",
                        "Keep the main face or action in the center of the screen."
                ))
                .howToMoveCamera(List.of(
                        movement.contains("Handheld") ? "Hold the phone with two hands and move slowly." : "Keep the phone still on a table, tripod, or stable surface.",
                        "If the shot feels shaky, record it again slower."
                ))
                .howToAct(List.of(
                        "Act like this is happening in real life.",
                        "Use small expressions instead of exaggerated acting."
                ))
                .editingTip("Cut right before the shot starts to feel slow.")
                .commonMistakes(List.of(
                        "Do not put text too low.",
                        "Do not overact.",
                        "Do not film with strong light behind your face."
                ))
                .phoneOnlyFriendly(true)
                .build();
    }

    private String sketchPromptFor(String shotType, String cameraAngle, String expression, String categoryCode, String action, String screenType) {
        String composition = "horizontal".equals(screenType) ? "horizontal 16:9 composition" : "vertical 9:16 composition";
        return "Monochrome cinematic storyboard sketch, " + shotType + ", " + cameraAngle
                + ", creator with " + expression
                + ", " + environmentFor(categoryCode)
                + ", natural soft lighting, action: " + action
                + ", filmmaking previsualization style, grayscale pencil storyboard aesthetic, " + composition;
    }

    private String formatTime(int seconds) {
        return "0:%02d".formatted(Math.max(0, seconds));
    }

    private String cleanBaseTitle(String title) {
        String value = defaultString(title, "Locked brief").replaceFirst("^\\d+\\.\\s*", "");
        return truncate(value, 96);
    }

    private String cameraForScene(int index) {
        return switch (index) {
            case 0 -> "Cold open close-up";
            case 1 -> "Insert detail";
            case 2 -> "Medium two-beat setup";
            case 3 -> "Handheld follow";
            case 4 -> "Reaction close-up";
            default -> "End frame wide";
        };
    }

    private String dialogueForScene(int index, String hook) {
        return switch (index) {
            case 0 -> "Wait... this is exactly where most people stop.";
            case 1 -> "I thought I needed a perfect plan.";
            case 2 -> "But the real problem was smaller than I made it.";
            case 3 -> "So I tried one version of it.";
            case 4 -> "That tiny shift changed the mood.";
            default -> "%s. Save this idea before you forget it.".formatted(hook);
        };
    }

    private String screenTextForScene(int index, String hook) {
        return switch (index) {
            case 0 -> hook.toUpperCase(Locale.ROOT);
            case 1 -> "THE EXCUSE";
            case 2 -> "THE REAL BLOCK";
            case 3 -> "ONE SMALL MOVE";
            case 4 -> "THE PAYOFF";
            default -> "SAVE THIS";
        };
    }

    private String directorNoteForScene(int index) {
        return switch (index) {
            case 0 -> "Start tight on face or hands. Hook should land in the first second.";
            case 1 -> "Use a concrete object so the audience understands the decision point visually.";
            case 2 -> "Keep the acting natural. This beat should feel observed, not performed.";
            case 3 -> "Move camera lightly with the creator so the action feels alive.";
            case 4 -> "Hold the reaction long enough for the viewer to feel the payoff.";
            default -> "Leave safe space at the bottom for platform UI and captions.";
        };
    }

    private String intentForScene(int index) {
        return switch (index) {
            case 0 -> "Stop the scroll with immediate recognition.";
            case 1 -> "Make the conflict visible.";
            case 2 -> "Build relatability before the turn.";
            case 3 -> "Show progress through action.";
            case 4 -> "Deliver emotional or comedic release.";
            default -> "Create a saveable ending.";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Integer integerValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> firstMapList(Map<String, Object> source, String... keys) {
        if (source == null) {
            return List.of();
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> rawTextIdeaMaps(Object rawText) {
        String text = stripJsonFence(stringValue(rawText).trim());
        if (text.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            List<Map<String, Object>> ideas = firstMapList(parsed, "ideas", "storyIdeas", "candidates", "items", "results");
            if (!ideas.isEmpty()) {
                return ideas;
            }
        } catch (JsonProcessingException ignored) {
            // Some providers return a useful JSON prefix even when the full response is truncated.
        }

        try {
            return objectMapper.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException ignored) {
            return salvageCompleteIdeaObjects(text);
        }
    }

    private String stripJsonFence(String text) {
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                return text.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        return text;
    }

    private List<Map<String, Object>> salvageCompleteIdeaObjects(String text) {
        int ideasIndex = text.indexOf("\"ideas\"");
        int arrayStart = ideasIndex >= 0 ? text.indexOf('[', ideasIndex) : text.indexOf('[');
        if (arrayStart < 0) {
            return List.of();
        }

        List<Map<String, Object>> ideas = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int index = arrayStart + 1; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == '{') {
                if (depth == 0) {
                    objectStart = index;
                }
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String objectJson = text.substring(objectStart, index + 1);
                    try {
                        ideas.add(objectMapper.readValue(objectJson, new TypeReference<LinkedHashMap<String, Object>>() {
                        }));
                    } catch (JsonProcessingException ignored) {
                        // Keep any later complete objects instead of failing the whole provider response.
                    }
                    objectStart = -1;
                }
            }
        }
        return ideas;
    }

    private Object firstValue(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string.split(","))
                    .stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringValue(Object value, String fallback) {
        String text = stringValue(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private record IdeaGenerationResult(
            int generatedCount,
            List<UUID> promptRunIds,
            List<Map<String, Object>> providerOutputs
    ) {
    }

    private record AiIdeaBatchResult(
            List<IdeaCandidate> candidates,
            UUID promptRunId,
            Map<String, Object> providerOutput
    ) {
    }

    private record GeneratedIdeaCandidate(
            IdeaCandidate candidate,
            UUID promptRunId
    ) {
    }

    private record ProductionPlanGenerationResult(
            List<ShotProductionPlanTagResponse> tags,
            String status,
            String error,
            Map<String, Object> debug
    ) {
        private ProductionPlanGenerationResult {
            tags = tags == null ? List.of() : tags;
            status = status == null || status.isBlank() ? "SKIPPED" : status;
            error = error == null ? "" : error;
            debug = debug == null ? Map.of() : debug;
        }
    }

    private record IdeaCandidate(
            String title,
            String summary,
            List<String> hashtags,
            Map<String, Object> creativeNotes
    ) {
        private String angle() {
            Object hook = creativeNotes == null ? null : creativeNotes.get("hook");
            return hook == null || String.valueOf(hook).isBlank() ? title : String.valueOf(hook);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("description", summary);
            payload.put("hashtags", hashtags == null ? List.of() : hashtags);
            payload.put("creativeNotes", creativeNotes == null ? Map.of() : creativeNotes);
            return payload;
        }
    }

    private String languageBucket(String dialogueLanguage) {
        String language = defaultString(dialogueLanguage, "").toLowerCase(Locale.ROOT);
        if (language.contains("hindi") || language.contains("hinglish") || language.contains("hi-in")) {
            return "hindi";
        }
        if (language.contains("tamil")) {
            return "tamil";
        }
        if (language.contains("telugu")) {
            return "telugu";
        }
        if (language.contains("bengali") || language.contains("bangla")) {
            return "bengali";
        }
        if (language.contains("marathi")) {
            return "marathi";
        }
        return "english";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
