package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.dto.request.GenerateStoryIdeaScriptRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryScriptRequest;
import com.dalai.llama.creator.dto.request.SaveGeneratedScriptRequest;
import com.dalai.llama.creator.dto.request.SaveStoryScriptRequest;
import com.dalai.llama.creator.dto.response.GeneratedIdeaResponse;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IdeaService {

    private static final int GENERATED_IDEA_COUNT = 20;
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
    private final PromptTemplateService promptTemplateService;
    private final CreatorAiService creatorAiService;
    private final GenerationJobService generationJobService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdeaService(
            CreatorIdeaRepository ideaRepository,
            CreatorScriptRepository scriptRepository,
            CreatorPromptRunRepository promptRunRepository,
            PromptTemplateService promptTemplateService,
            CreatorAiService creatorAiService,
            GenerationJobService generationJobService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.ideaRepository = ideaRepository;
        this.scriptRepository = scriptRepository;
        this.promptRunRepository = promptRunRepository;
        this.promptTemplateService = promptTemplateService;
        this.creatorAiService = creatorAiService;
        this.generationJobService = generationJobService;
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

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("lockedIdeaId", lockedIdeaId.toString());
        jobInput.put("lockedIdeaTitle", lockedIdea.getTitle());
        jobInput.put("durationSeconds", lockedIdea.getDurationSeconds());
        jobInput.put("source", lockedIdea.getSource());
        jobInput.put("selectionContext", lockedIdea.getSelectionContext());

        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.IDEA_GENERATE.name(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getProjectId(),
                jobInput
        );

        try {
            int generatedCount = ensureGeneratedIdeas(lockedIdea, generationJob.getId());
            Pageable normalizedPageable = normalizePageable(pageable);
            Page<GeneratedIdeaResponse> response = ideaRepository
                    .findGeneratedIdeasForLockedBrief(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable)
                    .map(this::toResponse);

            Map<String, Object> jobOutput = new LinkedHashMap<>();
            jobOutput.put("lockedIdeaId", lockedIdeaId.toString());
            jobOutput.put("generatedCount", generatedCount);
            jobOutput.put("returnedCount", response.getNumberOfElements());
            jobOutput.put("totalAvailable", response.getTotalElements());
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return response;
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            throw ex;
        }
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

        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds());
        String categoryCode = defaultString(request == null ? null : request.categoryCode(), inferCategory(storyIdea));
        String ideaText = defaultString(request == null ? null : request.idea(), storyIdea.getTitle() + "\n" + defaultString(storyIdea.getSummary(), ""));
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

            GeneratedStoryScriptResponse.StoryScript storyScript = buildStoryScriptPayload(
                    storyIdea,
                    durationSeconds,
                    categoryCode,
                    inferredTone,
                    dialogueLanguage,
                    screenType
            );
            Map<String, Object> storyScriptMap = toStoryScriptMap(storyScript);
            Map<String, Object> promptOutputPayload = new LinkedHashMap<>(storyScriptMap);
            promptOutputPayload.put("providerOutput", providerOutput);

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

            String scriptText = buildStoryScriptText(storyScript);
            CreatorIdea savedIdea = saveStoryScriptOnIdea(storyIdea, storyScript, scriptText, promptRun.getId(), generationJob.getId(), durationSeconds, dialogueLanguage, screenType);
            linkProjectSelectedIdea(savedIdea);

            Map<String, Object> jobOutput = new LinkedHashMap<>(promptOutputPayload);
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("storyIdeaId", savedIdea.getId().toString());
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return toGeneratedStoryScriptResponse(savedIdea, lockedIdeaId, promptRun.getId(), storyScript, scriptText);
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
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

        return toGeneratedStoryScriptResponse(savedIdea, lockedIdeaId, promptRunId, storyScript, scriptText);
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

        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds());
        String categoryCode = defaultString(request == null ? null : request.categoryCode(), inferCategory(storyIdea));
        String ideaText = defaultString(request == null ? null : request.idea(), storyIdea.getTitle() + "\n" + defaultString(storyIdea.getSummary(), ""));
        String dialogueLanguage = normalizeDialogueLanguage(request == null ? null : request.dialogueLanguage());
        String screenType = normalizeScreenType(request == null ? null : request.screenType());
        String inferredTone = inferTone(ideaText, categoryCode);
        GeneratedStoryScriptResponse.StoryScript storyScript = readStoryScriptFromIdea(storyIdea);
        if (storyScript == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate and save the story script before creating the shot-wise screenplay.");
        }

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.SCRIPT_GENERATE.name());
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
        inputSnapshot.put("storyScript", storyScript);
        inputSnapshot.put("context", request == null || request.context() == null ? Map.of() : request.context());

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

            GeneratedScriptResponse.CinematicScript scriptPayload = buildCinematicScriptPayload(storyIdea, storyScript, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType);
            scriptPayload.setProvider(creatorAiService.providerName());
            scriptPayload.setModel(creatorAiService.modelName());
            Map<String, Object> scriptPayloadMap = toMap(scriptPayload);
            Map<String, Object> promptOutputPayload = new LinkedHashMap<>(scriptPayloadMap);
            promptOutputPayload.put("providerOutput", providerOutput);

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

            storyIdea.setScript(script);
            storyIdea.setScenes(shotPayloads);
            storyIdea.setDurationSeconds(durationSeconds);
            storyIdea.setGenerationJobId(generationJob.getId());
            storyIdea.setSelectionContext(context);
            storyIdea.setStatus("SCRIPT_GENERATED");
            storyIdea.setSaved(true);
            storyIdea.setUpdatedAt(OffsetDateTime.now());
            CreatorIdea savedIdea = ideaRepository.save(storyIdea);
            linkProjectSelectedIdea(savedIdea);

            Map<String, Object> jobOutput = new LinkedHashMap<>(promptOutputPayload);
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("scriptId", creatorScript.getId().toString());
            jobOutput.put("storyIdeaId", savedIdea.getId().toString());
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return GeneratedScriptResponse.builder()
                    .scriptId(creatorScript.getId())
                    .ideaId(savedIdea.getId())
                    .lockedIdeaId(lockedIdeaId)
                    .promptRunId(promptRun.getId())
                    .title(savedIdea.getTitle())
                    .script(savedIdea.getScript())
                    .scriptJson(scriptPayload)
                    .scenes(shots)
                    .durationSeconds(savedIdea.getDurationSeconds())
                    .status(savedIdea.getStatus())
                    .generatedAt(savedIdea.getUpdatedAt())
                    .build();
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
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

        String title = defaultString(request == null ? null : request.title(), defaultString(scriptJson.getProjectTitle(), storyIdea.getTitle()));
        scriptJson.setProjectTitle(title);
        String scriptText = defaultString(request == null ? null : request.script(), buildScriptText(storyIdea, shots));
        Map<String, Object> scriptPayloadMap = toMap(scriptJson);
        List<Map<String, Object>> shotPayloads = toMapList(shots);

        creatorScript.setTitle(title);
        creatorScript.setDurationSeconds(durationSeconds);
        creatorScript.setDialogueLanguage(dialogueLanguage);
        creatorScript.setScreenType(screenType);
        creatorScript.setScriptText(scriptText);
        creatorScript.setScriptPayload(scriptPayloadMap);
        creatorScript.setShots(shotPayloads);
        creatorScript.setStatus("EDITED");
        creatorScript.setUpdatedAt(OffsetDateTime.now());
        CreatorScript savedScript = scriptRepository.save(creatorScript);

        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("scriptId", savedScript.getId().toString());
        context.put("scriptEditedAt", savedScript.getUpdatedAt().toString());
        context.put("scriptSceneCount", shots.size());
        context.put("scriptDurationSeconds", durationSeconds);
        context.put("scriptDialogueLanguage", dialogueLanguage);
        context.put("scriptScreenType", screenType);

        storyIdea.setTitle(title);
        storyIdea.setScript(scriptText);
        storyIdea.setScenes(shotPayloads);
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
                .promptRunId(savedScript.getPromptRunId())
                .title(savedIdea.getTitle())
                .script(savedIdea.getScript())
                .scriptJson(scriptJson)
                .scenes(shots)
                .durationSeconds(durationSeconds)
                .status(savedIdea.getStatus())
                .generatedAt(savedIdea.getUpdatedAt())
                .build();
    }

    private CreatorIdea getStoryIdeaForLockedBrief(UUID lockedIdeaId, UUID storyIdeaId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea storyIdea = ideaRepository.findById(storyIdeaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Story idea was not found."));
        if (!safeTenantId.equals(storyIdea.getTenantId()) || !safeUserId.equals(storyIdea.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story idea was not found.");
        }
        Map<String, Object> context = storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext();
        if (!lockedIdeaId.toString().equals(String.valueOf(context.get("parentLockedIdeaId")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Story idea does not belong to the locked brief.");
        }
        return storyIdea;
    }

    private int ensureGeneratedIdeas(CreatorIdea lockedIdea, UUID generationJobId) {
        Page<CreatorIdea> existing = ideaRepository.findGeneratedIdeasForLockedBrief(
                lockedIdea.getId(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                PageRequest.of(0, GENERATED_IDEA_COUNT)
        );
        int existingCount = (int) existing.getTotalElements();
        if (existingCount >= GENERATED_IDEA_COUNT) {
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        int generatedCount = 0;
        for (int index = existingCount; index < GENERATED_IDEA_COUNT; index++) {
            int ideaNumber = index + 1;
            String angle = IDEA_ANGLES.get(index % IDEA_ANGLES.size());
            Map<String, Object> context = buildGeneratedContext(lockedIdea, ideaNumber, angle);
            context.put("generationJobId", generationJobId.toString());
            ideaRepository.save(CreatorIdea.builder()
                    .tenantId(lockedIdea.getTenantId())
                    .userId(lockedIdea.getUserId())
                    .projectId(lockedIdea.getProjectId())
                    .trendId(lockedIdea.getTrendId())
                    .source("AI_FROM_LOCKED_BRIEF")
                    .title(buildTitle(lockedIdea, angle, ideaNumber))
                    .summary(buildSummary(lockedIdea, angle))
                    .durationSeconds(lockedIdea.getDurationSeconds())
                    .status("DRAFT")
                    .generationJobId(generationJobId)
                    .selectionContext(context)
                    .createdAt(now.plusNanos(ideaNumber))
                    .updatedAt(now.plusNanos(ideaNumber))
                    .build());
            generatedCount++;
        }
        return generatedCount;
    }

    private Map<String, Object> buildGeneratedContext(CreatorIdea lockedIdea, int ideaNumber, String angle) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("parentLockedIdeaId", lockedIdea.getId().toString());
        context.put("generatedIndex", ideaNumber);
        context.put("angle", angle);
        Map<String, Object> sourceBrief = new LinkedHashMap<>();
        sourceBrief.put("title", lockedIdea.getTitle());
        sourceBrief.put("summary", defaultString(lockedIdea.getSummary(), ""));
        sourceBrief.put("source", lockedIdea.getSource());
        sourceBrief.put("durationSeconds", lockedIdea.getDurationSeconds() == null ? 30 : lockedIdea.getDurationSeconds());
        sourceBrief.put("categoryCode", lockedIdea.getSelectionContext() == null ? null : lockedIdea.getSelectionContext().get("categoryCode"));
        context.put("sourceBrief", sourceBrief);
        context.put("hashtags", hashtagsFor(lockedIdea, angle));
        context.put("creativeNotes", Map.of(
                "hook", angle,
                "targetEmotion", targetEmotionFor(angle),
                "storyShape", storyShapeFor(angle),
                "selectionReason", "Generated from the locked trend/original brief so the creator can choose a stronger production angle."
        ));
        return context;
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

    private List<GeneratedStoryScriptResponse.CharacterProfile> characterProfilesFor(
            CreatorIdea storyIdea,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage
    ) {
        String category = defaultString(categoryCode, "creator").toLowerCase(Locale.ROOT);
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
            String scriptText
    ) {
        return GeneratedStoryScriptResponse.builder()
                .ideaId(savedIdea.getId())
                .lockedIdeaId(lockedIdeaId)
                .promptRunId(promptRunId)
                .title(savedIdea.getTitle())
                .scriptText(scriptText)
                .scriptJson(storyScript)
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
                .durationSeconds(Math.max(1, endSecond - startSecond))
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
                .dialogue(dialogueForPhase(phase, dialogueLanguage))
                .textOverlay(textOverlayFor(phase, dialogueLanguage))
                .transition(transitionFor(phase))
                .soundDesign(soundDesignFor(phase))
                .editingNotes(editingNotesFor(phase))
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
        for (GeneratedScriptResponse.CinematicShot scene : scenes) {
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

    private List<Map<String, Object>> toMapList(List<GeneratedScriptResponse.CinematicShot> shots) {
        return objectMapper.convertValue(shots, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private String dialogueText(Map<String, String> dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return "";
        }
        return dialogue.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
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
                       updated_at = now()
                 where id = ?
                   and tenant_id = ?
                   and user_id = ?
                """,
                idea.getId(),
                idea.getProjectId(),
                idea.getTenantId(),
                idea.getUserId()
        );
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
        if (duration <= 15) {
            return 15;
        }
        if (duration <= 30) {
            return 30;
        }
        if (duration <= 45) {
            return 45;
        }
        return 60;
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
        return "vertical";
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

    private String inferTone(String ideaText, String categoryCode) {
        String text = (defaultString(ideaText, "") + " " + defaultString(categoryCode, "")).toLowerCase(Locale.ROOT);
        if (text.matches(".*(funny|comedy|pov|saas|bahu|joke|relatable).*")) {
            return "relatable comedy with natural reactions";
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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
