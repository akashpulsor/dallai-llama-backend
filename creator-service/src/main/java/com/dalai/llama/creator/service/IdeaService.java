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
import com.dalai.llama.creator.domain.entity.CreatorTrendSignal;
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
import com.dalai.llama.creator.repository.CreatorTrendSignalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IdeaService {

    private static final Logger log = LoggerFactory.getLogger(IdeaService.class);

    private static final int MAX_GENERATED_IDEA_COUNT = 20;
    private static final int GENERATED_IDEA_AI_BATCH_SIZE = MAX_GENERATED_IDEA_COUNT;
    private static final int GENERATED_IDEA_AI_ATTEMPTS = 1;
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
    private static final List<Map<String, String>> PRODUCT_AD_CONCEPT_LANES = List.of(
            Map.of(
                    "key", "problem_solution",
                    "title", "Problem -> Solution",
                    "description", "Direct response concept that names the buyer pain, shows the product solving it, and closes with purchase intent."
            ),
            Map.of(
                    "key", "luxury_brand_story",
                    "title", "Luxury Brand Story",
                    "description", "Premium concept built around packaging, atmosphere, sensory detail, and high-trust brand perception."
            ),
            Map.of(
                    "key", "ugc_testimonial",
                    "title", "UGC/Testimonial Style",
                    "description", "Social-first concept that feels like a buyer discovery, proof moment, or creator recommendation."
            )
    );
    private static final List<Map<String, String>> NO_HUMAN_PRODUCT_AD_CONCEPT_LANES = List.of(
            Map.of(
                    "key", "problem_solution",
                    "title", "Product-Only Problem -> Solution",
                    "description", "Represent the problem and solution through product states, materials, typography, and CGI without a customer or presenter."
            ),
            Map.of(
                    "key", "luxury_brand_story",
                    "title", "Product-Only Luxury Story",
                    "description", "Build premium desire through packaging, atmosphere, macro texture, controlled lighting, and a hero packshot."
            ),
            Map.of(
                    "key", "ingredient_transformation",
                    "title", "Ingredient-to-Product Transformation",
                    "description", "Turn ingredients, materials, or product features into a kinetic CGI transformation that resolves on the canonical product."
            )
    );
    private static final String NO_HUMANS_NEGATIVE_PROMPT =
            "no people, no person, no face, no hands, no arms, no body, no human silhouette, "
                    + "no human reflection, no presenter, no customer, no creator, no human-operated product use";

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
    private final CreatorCreativeLearningService creativeLearningService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HookBeatPlanningService hookBeatPlanningService;
    private final ScriptCriticService scriptCriticService;
    private final CreatorTrendSignalRepository trendSignalRepository;

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
            CreatorCreativeLearningService creativeLearningService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            HookBeatPlanningService hookBeatPlanningService,
            ScriptCriticService scriptCriticService,
            CreatorTrendSignalRepository trendSignalRepository
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
        this.creativeLearningService = creativeLearningService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.hookBeatPlanningService = hookBeatPlanningService;
        this.scriptCriticService = scriptCriticService;
        this.trendSignalRepository = trendSignalRepository;
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

        Pageable normalizedPageable = normalizePageable(pageable);
        String idempotencyKey = ideaGenerationIdempotencyKey(lockedIdea, normalizedPageable);
        Map<String, Object> jobInput = buildIdeaGenerationJobInput(lockedIdea, normalizedPageable, idempotencyKey);
        if (hasGeneratedIdeasThroughPage(lockedIdea, normalizedPageable)) {
            log.info(
                    "Creator idea generation reused existing generated ideas lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);
        }
        CreatorGenerationJob activeJob = generationJobService
                .findActiveGenerationJobByIdempotencyKey(lockedIdea.getTenantId(), lockedIdea.getUserId(), PromptTemplateType.IDEA_GENERATE.name(), idempotencyKey)
                .orElse(null);
        if (activeJob != null) {
            log.info(
                    "Creator idea generation skipped duplicate direct execution activeJobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    activeJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);
        }

        CreatorGenerationJob generationJob;
        try {
            generationJob = generationJobService.startGenerationJob(
                    PromptTemplateType.IDEA_GENERATE.name(),
                    lockedIdea.getTenantId(),
                    lockedIdea.getUserId(),
                    lockedIdea.getProjectId(),
                    jobInput
            );
        } catch (DataIntegrityViolationException ex) {
            log.info(
                    "Creator idea generation duplicate direct request recovered lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);
        }
        log.info(
                "Creator idea generation job created jobId={} lockedIdeaId={} tenantId={} userId={} idempotencyKey={}",
                generationJob.getId(),
                lockedIdeaId,
                safeTenantId,
                safeUserId,
                idempotencyKey
        );

        try {
            IdeaGenerationResult generationResult = ensureGeneratedIdeas(lockedIdea, generationJob.getId(), normalizedPageable);
            Page<GeneratedIdeaResponse> response = generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);

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

        Pageable normalizedPageable = normalizePageable(pageable);
        String idempotencyKey = ideaGenerationIdempotencyKey(lockedIdea, normalizedPageable);
        Map<String, Object> jobInput = buildIdeaGenerationJobInput(lockedIdea, normalizedPageable, idempotencyKey);
        CreatorGenerationJob activeJob = generationJobService
                .findActiveGenerationJobByIdempotencyKey(lockedIdea.getTenantId(), lockedIdea.getUserId(), PromptTemplateType.IDEA_GENERATE.name(), idempotencyKey)
                .orElse(null);
        if (activeJob != null) {
            log.info(
                    "Creator async idea generation reused active job jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    activeJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return activeJob;
        }
        CreatorGenerationJob completedJob = generationJobService
                .findCompletedGenerationJobByIdempotencyKey(lockedIdea.getTenantId(), lockedIdea.getUserId(), PromptTemplateType.IDEA_GENERATE.name(), idempotencyKey)
                .orElse(null);
        if (completedJob != null && hasGeneratedIdeasThroughPage(lockedIdea, normalizedPageable)) {
            log.info(
                    "Creator async idea generation reused completed job jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    completedJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return completedJob;
        }
        if (hasGeneratedIdeasThroughPage(lockedIdea, normalizedPageable)) {
            CreatorGenerationJob cacheJob = generationJobService.startGenerationJob(
                    PromptTemplateType.IDEA_GENERATE.name(),
                    lockedIdea.getTenantId(),
                    lockedIdea.getUserId(),
                    lockedIdea.getProjectId(),
                    jobInput
            );
            Page<GeneratedIdeaResponse> response = generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);
            CreatorGenerationJob completedCacheJob = generationJobService.completeGenerationJob(
                    cacheJob.getId(),
                    buildIdeaGenerationJobOutput(lockedIdeaId, new IdeaGenerationResult(0, List.of(), List.of()), response)
            );
            log.info(
                    "Creator async idea generation created cache-hit completed job jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                    completedCacheJob.getId(),
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId,
                    normalizedPageable.getPageNumber(),
                    normalizedPageable.getPageSize(),
                    idempotencyKey
            );
            return completedCacheJob;
        }
        CreatorGenerationJob generationJob;
        try {
            generationJob = generationJobService.startGenerationJob(
                    PromptTemplateType.IDEA_GENERATE.name(),
                    lockedIdea.getTenantId(),
                    lockedIdea.getUserId(),
                    lockedIdea.getProjectId(),
                    jobInput
            );
        } catch (DataIntegrityViolationException ex) {
            CreatorGenerationJob recoveredJob = generationJobService
                    .findActiveGenerationJobByIdempotencyKey(lockedIdea.getTenantId(), lockedIdea.getUserId(), PromptTemplateType.IDEA_GENERATE.name(), idempotencyKey)
                    .orElse(null);
            if (recoveredJob == null) {
                recoveredJob = generationJobService
                        .findCompletedGenerationJobByIdempotencyKey(lockedIdea.getTenantId(), lockedIdea.getUserId(), PromptTemplateType.IDEA_GENERATE.name(), idempotencyKey)
                        .orElse(null);
            }
            if (recoveredJob != null) {
                log.info(
                        "Creator async idea generation duplicate recovered jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                        recoveredJob.getId(),
                        lockedIdeaId,
                        safeTenantId,
                        safeUserId,
                        normalizedPageable.getPageNumber(),
                        normalizedPageable.getPageSize(),
                        idempotencyKey
                );
                return recoveredJob;
            }
            throw ex;
        }
        log.info(
                "Creator async idea generation job accepted jobId={} lockedIdeaId={} tenantId={} userId={} page={} size={} idempotencyKey={}",
                generationJob.getId(),
                lockedIdeaId,
                safeTenantId,
                safeUserId,
                normalizedPageable.getPageNumber(),
                normalizedPageable.getPageSize(),
                idempotencyKey
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
        if (generationJobId != null && !generationJobService.claimGenerationJobExecution(generationJobId, "idea-generation")) {
            log.info(
                    "Skipping duplicate creator idea generation execution jobId={} lockedIdeaId={} tenantId={} userId={}",
                    generationJobId,
                    lockedIdeaId,
                    safeTenantId,
                    safeUserId
            );
            return;
        }
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

            Pageable normalizedPageable = normalizePageable(pageable);
            generationJobService.updateGenerationJobProgress(generationJobId, 15, "Generating story ideas with AI");
            IdeaGenerationResult generationResult = ensureGeneratedIdeas(lockedIdea, generationJobId, normalizedPageable);
            generationJobService.updateGenerationJobProgress(generationJobId, 82, "Preparing generated story ideas");

            Page<GeneratedIdeaResponse> response = generatedIdeasPage(lockedIdeaId, safeTenantId, safeUserId, normalizedPageable);

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

    private Map<String, Object> buildIdeaGenerationJobInput(CreatorIdea lockedIdea, Pageable pageable, String idempotencyKey) {
        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("lockedIdeaId", lockedIdea.getId().toString());
        if (lockedIdea.getProjectId() != null) {
            jobInput.put("projectId", lockedIdea.getProjectId().toString());
        }
        jobInput.put("lockedIdeaTitle", lockedIdea.getTitle());
        jobInput.put("durationSeconds", lockedIdea.getDurationSeconds());
        jobInput.put("source", lockedIdea.getSource());
        jobInput.put("selectionContext", lockedIdea.getSelectionContext());
        jobInput.putAll(productionStyleContextFromSelection(lockedIdea.getSelectionContext()));
        jobInput.put("page", pageable == null ? 0 : pageable.getPageNumber());
        jobInput.put("size", pageable == null ? DEFAULT_PAGE_SIZE : pageable.getPageSize());
        jobInput.put("targetGeneratedIdeaCount", targetGeneratedIdeaCount(pageable));
        jobInput.put("idempotencyKey", idempotencyKey);
        return jobInput;
    }

    private boolean hasGeneratedIdeasThroughPage(CreatorIdea lockedIdea, Pageable pageable) {
        int targetTotal = targetGeneratedIdeaCount(pageable);
        Page<CreatorIdea> existing = ideaRepository.findGeneratedIdeasForLockedBrief(
                lockedIdea.getId(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                PageRequest.of(0, 1)
        );
        return existing.getTotalElements() >= targetTotal;
    }

    private Page<GeneratedIdeaResponse> generatedIdeasPage(UUID lockedIdeaId, String tenantId, String userId, Pageable pageable) {
        Pageable normalizedPageable = normalizePageable(pageable);
        return ideaRepository
                .findGeneratedIdeasForLockedBrief(lockedIdeaId, tenantId, userId, normalizedPageable)
                .map(this::toResponse);
    }

    private int targetGeneratedIdeaCount(Pageable pageable) {
        return MAX_GENERATED_IDEA_COUNT;
    }

    private String ideaGenerationIdempotencyKey(CreatorIdea lockedIdea, Pageable pageable) {
        return "idea-generate:%s:count:%d".formatted(lockedIdea.getId(), MAX_GENERATED_IDEA_COUNT);
    }

    private void lockGeneratedIdeasForBrief(UUID lockedIdeaId) {
        jdbcTemplate.queryForList(
                "select pg_advisory_xact_lock(hashtext(?))",
                "creator-ideas:" + lockedIdeaId
        );
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
        String storytellingType = normalizeStorytellingType(request == null ? null : request.storytellingType());
        Map<String, Object> storytellingGuidance = storytellingGuidanceFor(storytellingType);
        String hookLens = normalizeHookLens(request == null ? null : request.hookLens());
        Map<String, Object> hookLensGuidance = hookLensGuidanceFor(hookLens);
        String inferredTone = inferTone(ideaText, categoryCode);
        Map<String, Object> productionStyleContext = productionStyleContextFromSelection(storyIdea.getSelectionContext());
        Map<String, Object> sourceBrief = buildSourceBrief(storyIdea);
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        List<Map<String, Object>> approvedCreativeLearnings = approvedCreativeLearningsFor(
                storyIdea,
                categoryCode,
                sourceBrief
        );
        if (isProductAdBrief(sourceBrief)) {
            inferredTone = stringValue(firstValue(productBrief, "adTone", "tone"), inferredTone);
        }

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.STORY_SCRIPT_GENERATE.name());
        Map<String, Object> beatPlan = hookBeatPlanningService.generateBeatPlan(
                ideaText, categoryCode, inferredTone, durationSeconds, productBrief,
                storyIdea.getTenantId(), storyIdea.getUserId(), storyIdea.getProjectId()
        );
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("duration", durationSeconds);
        inputSnapshot.put("idea", ideaText);
        inputSnapshot.put("category", categoryCode);
        inputSnapshot.put("dialogueLanguage", dialogueLanguage);
        inputSnapshot.put("screenType", screenType);
        inputSnapshot.put("storytellingType", storytellingType);
        inputSnapshot.put("storytellingGuidance", storytellingGuidance);
        inputSnapshot.put("hookLens", hookLens);
        inputSnapshot.put("hookLensGuidance", hookLensGuidance);
        inputSnapshot.putAll(productionStyleContext);
        inputSnapshot.put("productAdMode", isProductAdBrief(sourceBrief));
        inputSnapshot.put("noHumans", noHumans);
        inputSnapshot.put("productIntelligenceBrief", productBrief);
        inputSnapshot.put("approvedCreativeLearnings", approvedCreativeLearnings);
        putProductReferenceAiContext(inputSnapshot, sourceBrief);
        inputSnapshot.put("tone", inferredTone);
        inputSnapshot.put("lockedIdeaId", lockedIdeaId);
        inputSnapshot.put("storyIdeaId", storyIdeaId);
        inputSnapshot.put("storyIdea", toPromptIdeaMap(storyIdea));
        inputSnapshot.put("context", request == null || request.context() == null ? Map.of() : request.context());
        inputSnapshot.put("beatPlan", beatPlan);

        String renderedPrompt = promptTemplateService.render(template, inputSnapshot);
        renderedPrompt = appendNoHumanProductPrompt(renderedPrompt, noHumans, "story");
        renderedPrompt = appendProductReferencePrompt(renderedPrompt, sourceBrief, "story script");
        renderedPrompt = appendApprovedCreativeLearningPrompt(renderedPrompt, approvedCreativeLearnings);
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
            // Critique-and-retry: generate up to 3 attempts, score each against the approved beat
            // plan/product brief, keep the best-scored attempt rather than blindly taking the last
            // one - same pattern as every other critic in this graph (CampaignAngleSuggestionService,
            // ProductionPlanTagService's shot-plan regeneration).
            CreatorAiService.MeteredAiResponse aiResponse = null;
            Map<String, Object> providerOutput = null;
            GeneratedStoryScriptResponse.StoryScript storyScript = null;
            Map<String, Object> bestAiOutputDiagnostics = new LinkedHashMap<>();
            double bestScore = -1;
            String priorFeedback = "";
            for (int attempt = 1; attempt <= 3; attempt++) {
                Map<String, Object> attemptProviderInput = new LinkedHashMap<>(providerInput);
                if (!priorFeedback.isBlank()) {
                    attemptProviderInput.put("renderedPrompt", renderedPrompt
                            + "\n\nCRITIC FEEDBACK FROM A PRIOR ATTEMPT - fix these specific issues this time:\n" + priorFeedback);
                }
                CreatorAiService.MeteredAiResponse attemptResponse =
                        creatorAiService.generateMetered(PromptTemplateType.STORY_SCRIPT_GENERATE.name(), attemptProviderInput, usageContext);
                Map<String, Object> attemptOutput = attemptResponse.output();
                Map<String, Object> attemptDiagnostics = new LinkedHashMap<>();
                GeneratedStoryScriptResponse.StoryScript attemptStoryScript = resolveStoryScriptPayload(
                        attemptOutput,
                        storyIdea,
                        durationSeconds,
                        categoryCode,
                        inferredTone,
                        dialogueLanguage,
                        screenType,
                        storytellingType,
                        storytellingGuidance,
                        hookLens,
                        hookLensGuidance,
                        noHumans,
                        attemptDiagnostics
                );
                if (noHumans) {
                    applyNoHumanProductStoryContract(attemptStoryScript, storyIdea, sourceBrief, durationSeconds);
                }
                ScriptCriticService.ScriptCriticResult critique = null;
                try {
                    critique = scriptCriticService.critique(
                            toStoryScriptMap(attemptStoryScript), beatPlan, productBrief,
                            storyIdea.getTenantId(), storyIdea.getUserId(), storyIdea.getProjectId()
                    );
                } catch (RuntimeException ex) {
                    log.warn("Story script critique failed, accepting this attempt as-is errorType={} errorMessage={}",
                            ex.getClass().getSimpleName(), ex.getMessage());
                }
                double score = critique == null ? 100.0 : critique.averageScore();
                if (aiResponse == null || score > bestScore) {
                    aiResponse = attemptResponse;
                    providerOutput = attemptOutput;
                    storyScript = attemptStoryScript;
                    bestAiOutputDiagnostics = attemptDiagnostics;
                    bestScore = score;
                }
                if (critique == null || !critique.isFail()) {
                    break;
                }
                priorFeedback = critique.issues().isEmpty() ? critique.summary() : String.join("; ", critique.issues());
            }
            aiOutputDiagnostics.putAll(bestAiOutputDiagnostics);
            providerOutputForDebug = copyDebugMap(providerOutput);

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
            CreatorIdea savedIdea = saveStoryScriptOnIdea(storyIdea, storyScript, scriptText, promptRun.getId(), generationJob.getId(), durationSeconds, dialogueLanguage, screenType, storytellingType, hookLens);
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
        boolean synthesizedStoryScript = false;
        if (storyScript == null) {
            storyScript = readStoryScriptFromIdea(storyIdea);
        }
        if (storyScript == null) {
            synthesizedStoryScript = true;
            storyScript = buildStoryScriptPayload(
                    storyIdea,
                    normalizeDuration(request == null ? null : request.durationSeconds(), storyIdea.getDurationSeconds()),
                    inferCategory(storyIdea),
                    inferTone(storyIdea.getTitle() + " " + defaultString(storyIdea.getSummary(), ""), inferCategory(storyIdea)),
                    normalizeDialogueLanguage(request == null ? null : request.dialogueLanguage()),
                    normalizeScreenType(request == null ? null : request.screenType()),
                    normalizeStorytellingType(request == null ? null : request.storytellingType()),
                    normalizeHookLens(request == null ? null : request.hookLens())
            );
        }

        int durationSeconds = normalizeDuration(request == null ? null : request.durationSeconds(), storyScript.getDuration());
        String dialogueLanguage = normalizeDialogueLanguage(defaultString(request == null ? null : request.dialogueLanguage(), storyScript.getDialogueLanguage()));
        String screenType = normalizeScreenType(defaultString(request == null ? null : request.screenType(), storyScript.getScreenType()));
        String storytellingType = normalizeStorytellingType(defaultString(request == null ? null : request.storytellingType(), storyScript.getStorytellingType()));
        String hookLens = normalizeHookLens(defaultString(request == null ? null : request.hookLens(), storyScript.getHookLens()));
        String title = defaultString(request == null ? null : request.title(), defaultString(storyScript.getProjectTitle(), storyIdea.getTitle()));
        storyScript.setProjectTitle(title);
        storyScript.setDuration(durationSeconds);
        storyScript.setDialogueLanguage(dialogueLanguage);
        storyScript.setScreenType(screenType);
        storyScript.setStorytellingType(storytellingType);
        storyScript.setStorytellingGuidance(nonEmptyMap(storyScript.getStorytellingGuidance(), storytellingGuidanceFor(storytellingType)));
        storyScript.setHookLens(hookLens);
        storyScript.setHookLensGuidance(nonEmptyMap(storyScript.getHookLensGuidance(), hookLensGuidanceFor(hookLens)));
        storyScript.setHookBridge(nonEmptyMap(storyScript.getHookBridge(), defaultHookBridgeFor(hookLens)));
        storyScript.setFactualityNotes(nonEmptyMap(storyScript.getFactualityNotes(), defaultFactualityNotesFor(hookLens)));
        Map<String, Object> sourceBrief = ideaRepository.findById(lockedIdeaId)
                .map(this::buildSourceBrief)
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
        sourceBrief.putAll(buildSourceBrief(storyIdea));
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        if (noHumans) {
            if (synthesizedStoryScript) {
                applyNoHumanProductStoryContract(storyScript, storyIdea, sourceBrief, durationSeconds);
            } else {
                applyNoHumanProductStorySaveContract(storyScript);
            }
        }

        String scriptText = noHumans
                ? buildStoryScriptText(storyScript)
                : defaultString(request == null ? null : request.scriptText(), buildStoryScriptText(storyScript));
        UUID promptRunId = storyIdea.getPromptRunId();
        CreatorIdea savedIdea = saveStoryScriptOnIdea(storyIdea, storyScript, scriptText, promptRunId, storyIdea.getGenerationJobId(), durationSeconds, dialogueLanguage, screenType, storytellingType, hookLens);
        linkProjectSelectedIdea(savedIdea);
        log.info(
                "Creator story script saved storyIdeaId={} lockedIdeaId={} noHumans={} storylineChars={} revisionNumber={}",
                savedIdea.getId(),
                lockedIdeaId,
                noHumans,
                defaultString(storyScript.getStoryline(), "").length(),
                storyScript.getRevisionAudit() == null ? null : storyScript.getRevisionAudit().get("revisionNumber")
        );

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
        return generateScriptForStoryIdeaInternal(lockedIdeaId, storyIdeaId, request, tenantId, userId, null, true);
    }

    @Transactional
    public GeneratedScriptResponse generateScriptForStoryIdeaForJob(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryIdeaScriptRequest request,
            String tenantId,
            String userId,
            UUID generationJobId
    ) {
        return generateScriptForStoryIdeaInternal(lockedIdeaId, storyIdeaId, request, tenantId, userId, generationJobId, false);
    }

    private GeneratedScriptResponse generateScriptForStoryIdeaInternal(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryIdeaScriptRequest request,
            String tenantId,
            String userId,
            UUID externalGenerationJobId,
            boolean manageGenerationJob
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
        Map<String, Object> sourceBrief = buildSourceBrief(storyIdea);
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        if (noHumans) {
            applyNoHumanProductStoryContract(storyScript, storyIdea, sourceBrief, durationSeconds);
        }
        String categoryCode = resolveScreenplayCategory(request == null ? null : request.categoryCode(), storyIdea, storyScript, ideaText);
        List<Map<String, Object>> approvedCreativeLearnings = approvedCreativeLearningsFor(
                storyIdea,
                categoryCode,
                sourceBrief
        );
        String inferredTone = inferTone(ideaText, categoryCode);
        if (isProductAdBrief(sourceBrief)) {
            inferredTone = stringValue(firstValue(productBrief, "adTone", "tone"), inferredTone);
        }
        Map<String, Object> requestContext = request == null ? Map.of() : toGenericMap(request.context());
        Map<String, Object> lockedPackageContext = mapValue(requestContext.get("lockedPackage"));
        Map<String, Object> inheritedProductionContext = productionStyleContextFromSelection(storyIdea.getSelectionContext());
        if (inheritedProductionContext.isEmpty()) {
            inheritedProductionContext = productionStyleContextFromSelection(mapValue(storyIdea.getSelectionContext() == null ? null : storyIdea.getSelectionContext().get("sourceBrief")));
        }
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
        creatorContext = mergeDefaults(creatorContext, inheritedProductionContext);
        String productionStyle = resolveScreenplayProductionStyle(request, requestContext, lockedPackageContext, creatorContext);
        String hybridSceneMode = resolveScreenplayHybridSceneMode(request, requestContext, lockedPackageContext, creatorContext);
        String brollStyle = resolveScreenplayBrollStyle(request, requestContext, lockedPackageContext, creatorContext);
        String captionStyle = resolveScreenplayCaptionStyle(request, requestContext, lockedPackageContext, creatorContext);
        Map<String, Object> productionStyleGuidance = resolveScreenplayProductionStyleGuidance(
                request,
                requestContext,
                lockedPackageContext,
                creatorContext,
                productionStyle,
                hybridSceneMode,
                brollStyle,
                captionStyle
        );
        String storytellingType = resolveScreenplayStorytellingType(request, requestContext, lockedPackageContext, creatorContext, storyScript);
        Map<String, Object> storytellingGuidance = storytellingGuidanceFor(storytellingType);
        String hookLens = resolveScreenplayHookLens(request, requestContext, lockedPackageContext, creatorContext, storyScript);
        Map<String, Object> hookLensGuidance = hookLensGuidanceFor(hookLens);
        storyScript.setStorytellingType(storytellingType);
        storyScript.setStorytellingGuidance(nonEmptyMap(storyScript.getStorytellingGuidance(), storytellingGuidance));
        storyScript.setHookLens(hookLens);
        storyScript.setHookLensGuidance(nonEmptyMap(storyScript.getHookLensGuidance(), hookLensGuidance));
        storyScript.setHookBridge(nonEmptyMap(storyScript.getHookBridge(), defaultHookBridgeFor(hookLens)));
        storyScript.setFactualityNotes(nonEmptyMap(storyScript.getFactualityNotes(), defaultFactualityNotesFor(hookLens)));
        creatorContext = new LinkedHashMap<>(creatorContext);
        creatorContext.putIfAbsent("storytellingType", storytellingType);
        creatorContext.putIfAbsent("storytellingGuidance", storytellingGuidance);
        creatorContext.putIfAbsent("hookLens", hookLens);
        creatorContext.putIfAbsent("hookLensGuidance", hookLensGuidance);
        creatorContext.putIfAbsent("productionStyle", productionStyle);
        creatorContext.putIfAbsent("hybridSceneMode", hybridSceneMode);
        creatorContext.putIfAbsent("brollStyle", brollStyle);
        creatorContext.putIfAbsent("captionStyle", captionStyle);
        creatorContext.putIfAbsent("productionStyleGuidance", productionStyleGuidance);
        if (!approvedCreativeLearnings.isEmpty()) {
            creatorContext.put("approvedCreativeLearnings", approvedCreativeLearnings);
            creatorContext.put("creativeLearningApplied", true);
        }
        if (isProductAdBrief(sourceBrief)) {
            List<String> productReferenceImageUrls = productReferenceImageUrls(sourceBrief);
            creatorContext.put("productIntelligenceBrief", productBrief);
            creatorContext.put("productImageUrls", productReferenceImageUrls);
            creatorContext.put("referenceImageUrls", productReferenceImageUrls);
            creatorContext.put("productImageAssets", productReferenceImageAssets(sourceBrief));
            creatorContext.put("referenceImageAssets", productReferenceImageAssets(sourceBrief));
        }

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.SCRIPT_GENERATE.name());
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("duration", durationSeconds);
        inputSnapshot.put("idea", ideaText);
        inputSnapshot.put("category", categoryCode);
        inputSnapshot.put("dialogueLanguage", dialogueLanguage);
        inputSnapshot.put("screenType", screenType);
        inputSnapshot.put("storytellingType", storytellingType);
        inputSnapshot.put("storytellingGuidance", storytellingGuidance);
        inputSnapshot.put("hookLens", hookLens);
        inputSnapshot.put("hookLensGuidance", hookLensGuidance);
        inputSnapshot.put("productAdMode", isProductAdBrief(sourceBrief));
        inputSnapshot.put("noHumans", noHumans);
        inputSnapshot.put("productIntelligenceBrief", productBrief);
        inputSnapshot.put("approvedCreativeLearnings", approvedCreativeLearnings);
        putProductReferenceAiContext(inputSnapshot, sourceBrief);
        inputSnapshot.put("productionStyle", productionStyle);
        inputSnapshot.put("hybridSceneMode", hybridSceneMode);
        inputSnapshot.put("brollStyle", brollStyle);
        inputSnapshot.put("captionStyle", captionStyle);
        inputSnapshot.put("productionStyleGuidance", productionStyleGuidance);
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
        // The story script stage already planned+critiqued its own hook/beats (Phase 3a runs there,
        // not here) - the screenplay stage builds shots from that already-approved structure rather
        // than generating a second, independent beat plan.
        Map<String, Object> beatPlan = new LinkedHashMap<>();
        if (!stringValue(storyScript.getHook()).isBlank()) {
            beatPlan.put("hookLine", storyScript.getHook());
        }
        if (storyBeats != null && !storyBeats.isEmpty()) {
            beatPlan.put("beats", storyBeats);
        }
        inputSnapshot.put("beatPlan", beatPlan);

        String renderedPrompt = promptTemplateService.render(template, inputSnapshot);
        renderedPrompt = appendNoHumanProductPrompt(renderedPrompt, noHumans, "screenplay");
        renderedPrompt = appendProductReferencePrompt(renderedPrompt, sourceBrief, "screenplay");
        renderedPrompt = appendApprovedCreativeLearningPrompt(renderedPrompt, approvedCreativeLearnings);
        Map<String, Object> providerInput = new LinkedHashMap<>(inputSnapshot);
        providerInput.put("renderedPrompt", renderedPrompt);
        CreatorGenerationJob generationJob = externalGenerationJobId == null
                ? generationJobService.startGenerationJob(
                        PromptTemplateType.SCRIPT_GENERATE.name(),
                        storyIdea.getTenantId(),
                        storyIdea.getUserId(),
                        storyIdea.getProjectId(),
                        providerInput
                )
                : generationJobService.findGenerationJob(externalGenerationJobId)
                        .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + externalGenerationJobId));

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
                scriptPayload = resolveCinematicScriptPayload(providerOutput, storyIdea, storyScript, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, storytellingType, storytellingGuidance, hookLens, hookLensGuidance, aiOutputDiagnostics);
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
                    scriptPayload = resolveCinematicScriptPayload(retryProviderOutput, storyIdea, storyScript, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, storytellingType, storytellingGuidance, hookLens, hookLensGuidance, retryDiagnostics);
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
            applyProductionStyleDefaults(scriptPayload, productionStyle, hybridSceneMode, brollStyle, captionStyle, productionStyleGuidance);
            enrichAudioAndMusicDesign(scriptPayload, categoryCode, inferredTone);
            enrichVideoGenerationPlan(scriptPayload, categoryCode, screenType, storytellingType);
            if (noHumans) {
                applyNoHumanProductScreenplayContract(scriptPayload);
            }
            Map<String, Object> scriptPayloadMap = toMap(scriptPayload);
            putStoryStructure(scriptPayloadMap, storyScript);
            putScreenplayPlanningContext(scriptPayloadMap, budgetTier, characterCastMappings, availableActors, audienceDecision, brandContext, creatorContext);
            putProductReferencePersistence(scriptPayloadMap, sourceBrief);
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
            context.put("scriptStorytellingType", storytellingType);
            context.put("scriptHookLens", hookLens);
            context.put("productionStyle", productionStyle);
            context.put("hybridSceneMode", hybridSceneMode);
            context.put("brollStyle", brollStyle);
            context.put("captionStyle", captionStyle);
            context.put("productionStyleGuidance", productionStyleGuidance);
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
            // Post-hoc, non-blocking critique: this stage already has its own parse-failure retry
            // (shouldRetryScreenplayGeneration above) and ShotPlanCriticService independently scores
            // beat fidelity/dialogue/emotional arc once shots are planned - this call surfaces a
            // script-level verdict for visibility/trace purposes without adding a second regeneration
            // loop on top of an already-complex generation path.
            try {
                ScriptCriticService.ScriptCriticResult screenplayCritique = scriptCriticService.critique(
                        enrichedScriptPayloadMap, beatPlan, productBrief, storyIdea.getTenantId(), storyIdea.getUserId(), storyIdea.getProjectId()
                );
                jobOutput.put("scriptCritique", Map.of(
                        "status", screenplayCritique.status(),
                        "averageScore", screenplayCritique.averageScore(),
                        "issues", screenplayCritique.issues(),
                        "summary", screenplayCritique.summary()
                ));
            } catch (RuntimeException ex) {
                log.warn("Screenplay critique failed, continuing without it scriptId={} errorType={} errorMessage={}",
                        creatorScript.getId(), ex.getClass().getSimpleName(), ex.getMessage());
            }
            if (manageGenerationJob) {
                generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);
            }

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
            if (manageGenerationJob) {
                generationJobService.failGenerationJob(
                        generationJob.getId(),
                        defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                        rawPromptFailureOutput(ex, PromptTemplateType.SCRIPT_GENERATE.name(), providerOutputForDebug, aiOutputDiagnostics)
                );
            }
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
                .findByIdAndTenantIdAndUserIdForUpdate(scriptId, storyIdea.getTenantId(), storyIdea.getUserId())
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
        String storytellingType = normalizeStorytellingType(defaultString(request == null ? null : request.storytellingType(), scriptJson.getStorytellingType()));
        scriptJson.setStorytellingType(storytellingType);
        scriptJson.setStorytellingGuidance(nonEmptyMap(scriptJson.getStorytellingGuidance(), storytellingGuidanceFor(storytellingType)));
        scriptJson.setShotMixPlan(nonEmptyMap(scriptJson.getShotMixPlan(), shotMixPlanFor(storytellingType)));
        String hookLens = normalizeHookLens(defaultString(request == null ? null : request.hookLens(), scriptJson.getHookLens()));
        scriptJson.setHookLens(hookLens);
        scriptJson.setHookLensGuidance(nonEmptyMap(scriptJson.getHookLensGuidance(), hookLensGuidanceFor(hookLens)));
        scriptJson.setHookBridge(nonEmptyMap(scriptJson.getHookBridge(), defaultHookBridgeFor(hookLens)));
        scriptJson.setFactualityNotes(nonEmptyMap(scriptJson.getFactualityNotes(), defaultFactualityNotesFor(hookLens)));
        String productionStyle = normalizeProductionStyle(defaultString(request == null ? null : request.productionStyle(), scriptJson.getProductionStyle()));
        String hybridSceneMode = normalizeHybridSceneMode(defaultString(request == null ? null : request.hybridSceneMode(), scriptJson.getHybridSceneMode()));
        String brollStyle = normalizeBrollStyle(defaultString(request == null ? null : request.brollStyle(), scriptJson.getBrollStyle()));
        String captionStyle = normalizeCaptionStyle(defaultString(request == null ? null : request.captionStyle(), scriptJson.getCaptionStyle()));
        Map<String, Object> productionStyleGuidance = nonEmptyMap(
                request == null ? null : request.productionStyleGuidance(),
                scriptJson.getProductionStyleGuidance()
        );
        applyShotStorytellingDefaults(shots, storytellingType);
        applyProductionStyleDefaults(scriptJson, productionStyle, hybridSceneMode, brollStyle, captionStyle, productionStyleGuidance);
        stripEmbeddedProductionPlanTags(scriptJson);
        Map<String, Object> sourceBrief = buildSourceBrief(storyIdea);
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        if (noHumans) {
            applyNoHumanProductScreenplayContract(scriptJson);
        }

        String title = defaultString(request == null ? null : request.title(), defaultString(scriptJson.getProjectTitle(), storyIdea.getTitle()));
        scriptJson.setProjectTitle(title);
        String scriptText = noHumans
                ? buildScriptText(storyIdea, shots)
                : defaultString(request == null ? null : request.script(), buildScriptText(storyIdea, shots));
        String categoryCode = defaultString(scriptJson.getCategory(), creatorScript.getCategoryCode());
        String inferredTone = defaultString(scriptJson.getInferredTone(), inferTone(scriptText, categoryCode));
        enrichAudioAndMusicDesign(scriptJson, categoryCode, inferredTone);
        enrichVideoGenerationPlan(scriptJson, categoryCode, screenType, storytellingType);
        if (noHumans) {
            applyNoHumanProductScreenplayContract(scriptJson);
        }
        Map<String, Object> scriptPayloadMap = toMap(scriptJson);
        GeneratedStoryScriptResponse.StoryScript storyScript = readStoryScriptFromIdea(storyIdea);
        if (storyScript != null) {
            storyScript.setStorytellingType(storytellingType);
            storyScript.setStorytellingGuidance(nonEmptyMap(storyScript.getStorytellingGuidance(), scriptJson.getStorytellingGuidance()));
            storyScript.setHookLens(hookLens);
            storyScript.setHookLensGuidance(nonEmptyMap(storyScript.getHookLensGuidance(), scriptJson.getHookLensGuidance()));
            storyScript.setHookBridge(nonEmptyMap(storyScript.getHookBridge(), scriptJson.getHookBridge()));
            storyScript.setFactualityNotes(nonEmptyMap(storyScript.getFactualityNotes(), scriptJson.getFactualityNotes()));
        }
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
        context.put("scriptStorytellingType", storytellingType);
        context.put("scriptHookLens", hookLens);
        context.put("productionStyle", productionStyle);
        context.put("hybridSceneMode", hybridSceneMode);
        context.put("brollStyle", brollStyle);
        context.put("captionStyle", captionStyle);
        context.put("productionStyleGuidance", scriptJson.getProductionStyleGuidance());
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

    @Transactional
    public GeneratedScriptResponse approveScreenplayForVideo(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            UUID scriptId,
            Map<String, Object> request,
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

        Map<String, Object> scriptPayload = new LinkedHashMap<>(creatorScript.getScriptPayload() == null ? Map.of() : creatorScript.getScriptPayload());
        List<Map<String, Object>> shotPayloads = new ArrayList<>(creatorScript.getShots() == null ? List.of() : creatorScript.getShots());
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approved", true);
        approval.put("approvedAt", OffsetDateTime.now().toString());
        approval.put("approvedBy", defaultString(userId, "anonymous"));
        approval.put("approvalSource", stringValue(request == null ? null : request.get("approvalSource"), "creator_ui_video_generation"));
        approval.put("targetProvider", stringValue(request == null ? null : request.get("targetProvider"), "seedance"));
        approval.put("maxClipSeconds", integerValue(request == null ? null : request.get("maxClipSeconds"), 15));
        approval.put("durationSeconds", integerValue(request == null ? null : request.get("durationSeconds"), creatorScript.getDurationSeconds()));
        approval.put("screenType", stringValue(request == null ? null : request.get("screenType"), creatorScript.getScreenType()));

        scriptPayload.put("screenplayApprovedForVideo", true);
        scriptPayload.put("screenplayApproval", approval);
        scriptPayload.put("videoApproval", approval);
        scriptPayload.put("targetProvider", approval.get("targetProvider"));
        scriptPayload.put("maxClipSeconds", approval.get("maxClipSeconds"));
        if (request != null && request.get("productionStyle") != null) {
            scriptPayload.put("productionStyle", normalizeProductionStyle(stringValue(request.get("productionStyle"))));
        }
        if (request != null && request.get("productionStyleGuidance") != null) {
            scriptPayload.put("productionStyleGuidance", request.get("productionStyleGuidance"));
        }

        creatorScript.setScriptPayload(scriptPayload);
        creatorScript.setStatus("SCREENPLAY_APPROVED");
        creatorScript.setUpdatedAt(OffsetDateTime.now());
        CreatorScript savedScript = scriptRepository.save(creatorScript);

        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("scriptId", savedScript.getId().toString());
        context.put("screenplayApprovedForVideo", true);
        context.put("screenplayApproval", approval);
        context.put("videoApproval", approval);
        context.put("screenplayApprovedAt", approval.get("approvedAt"));
        context.put("screenplayStatus", savedScript.getStatus());
        storyIdea.setSelectionContext(context);
        storyIdea.setStatus("SCREENPLAY_APPROVED");
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        CreatorIdea savedIdea = ideaRepository.save(storyIdea);
        linkProjectSelectedIdea(savedIdea);

        GeneratedScriptResponse.CinematicScript scriptJson = objectMapper.convertValue(scriptPayload, GeneratedScriptResponse.CinematicScript.class);
        List<GeneratedScriptResponse.CinematicShot> shots = objectMapper.convertValue(shotPayloads, new TypeReference<List<GeneratedScriptResponse.CinematicShot>>() {
        });
        scriptJson.setShots(shots);

        return GeneratedScriptResponse.builder()
                .scriptId(savedScript.getId())
                .ideaId(savedIdea.getId())
                .lockedIdeaId(lockedIdeaId)
                .projectId(savedIdea.getProjectId())
                .promptRunId(savedScript.getPromptRunId())
                .title(savedScript.getTitle())
                .script(savedScript.getScriptText())
                .scriptJson(scriptJson)
                .scenes(shots)
                .durationSeconds(savedScript.getDurationSeconds())
                .status(savedScript.getStatus())
                .generatedAt(savedScript.getUpdatedAt())
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

    private IdeaGenerationResult ensureGeneratedIdeas(CreatorIdea lockedIdea, UUID generationJobId, Pageable pageable) {
        Pageable normalizedPageable = normalizePageable(pageable);
        int targetTotal = targetGeneratedIdeaCount(normalizedPageable);
        lockGeneratedIdeasForBrief(lockedIdea.getId());
        Page<CreatorIdea> existing = ideaRepository.findGeneratedIdeasForLockedBrief(
                lockedIdea.getId(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                PageRequest.of(0, targetTotal)
        );
        int existingCount = (int) existing.getTotalElements();
        if (existingCount >= targetTotal) {
            return new IdeaGenerationResult(0, List.of(), List.of());
        }

        int targetCount = targetTotal - existingCount;
        Map<String, Object> sourceBrief = buildSourceBrief(lockedIdea);
        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.IDEA_GENERATE.name());

        List<GeneratedIdeaCandidate> generatedCandidates = new ArrayList<>();
        List<UUID> promptRunIds = new ArrayList<>();
        List<Map<String, Object>> providerOutputs = new ArrayList<>();
        int maxAttempts = GENERATED_IDEA_AI_ATTEMPTS;

        for (int attempt = 0; generatedCandidates.size() < targetCount && attempt < maxAttempts; attempt++) {
            int batchSize = Math.min(GENERATED_IDEA_AI_BATCH_SIZE, targetCount - generatedCandidates.size());
            AiIdeaBatchResult batchResult = generateIdeaBatch(
                    lockedIdea,
                    generationJobId,
                    template,
                    sourceBrief,
                    existingCount,
                    generatedCandidates.size(),
                    targetTotal,
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
            int targetTotal,
            int candidateCount
    ) {
        List<String> batchAngles = ideaAnglesForBatch(sourceBrief, existingCount + alreadyGeneratedCount, candidateCount);
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("lockedIdeaId", lockedIdea.getId().toString());
        inputSnapshot.put("candidateCount", candidateCount);
        inputSnapshot.put("totalCandidateTarget", targetTotal);
        inputSnapshot.put("existingCandidateCount", existingCount + alreadyGeneratedCount);
        inputSnapshot.put("durationSeconds", lockedIdea.getDurationSeconds() == null ? 30 : lockedIdea.getDurationSeconds());
        inputSnapshot.put("noHumans", noHumans);
        inputSnapshot.putAll(productionStyleContextFromSelection(sourceBrief));
        inputSnapshot.put("lockedBrief", sourceBrief);
        List<Map<String, Object>> approvedCreativeLearnings = approvedCreativeLearningsFor(
                lockedIdea,
                stringValue(firstValue(sourceBrief, "categoryCode", "category")),
                sourceBrief
        );
        inputSnapshot.put("approvedCreativeLearnings", approvedCreativeLearnings);
        putProductReferenceAiContext(inputSnapshot, sourceBrief);
        inputSnapshot.put("ideaAngles", batchAngles);
        Map<String, Object> selectedCampaignAngle = mapValue(firstSelectionContextValue(sourceBrief, "campaignAngle"));
        inputSnapshot.put("selectedCampaignAngle", selectedCampaignAngle);
        List<String> generationRules = new ArrayList<>(isProductAdBrief(sourceBrief) ? List.of(
                "Generate distinct product-ad campaign concepts from the locked product brief.",
                noHumans
                        ? "The first concepts should cover Product-Only Problem -> Solution, Product-Only Luxury Story, and Ingredient-to-Product Transformation."
                        : "The first concepts should cover Problem -> Solution, Luxury Brand Story, and UGC/Testimonial Style.",
                "Include compact marketing strategy, shot planner, image prompts, video prompt plan, voice/music/caption direction, and editor plan in creativeNotes.",
                "Inspect the attached product images together with ingredients, audience, and objective; choose and return adTone plus toneRationale in each idea's creativeNotes.",
                "Return JSON only with an ideas array."
        ) : List.of(
                "Generate distinct short-form story ideas from the locked brief.",
                "Each idea must be practical for a beginner creator using a phone.",
                "Do not return a screenplay or storyboard yet.",
                "Return JSON only with an ideas array."
        ));
        if (!selectedCampaignAngle.isEmpty()) {
            generationRules.add("The selected campaign angle is mandatory for every returned idea. Preserve its persuasion mechanism, hook, visual direction, and promised payoff; vary only the execution.");
        }
        if (noHumans) {
            generationRules.add("NO HUMANS is a hard narrative and visual constraint. Do not create a named or unnamed customer, protagonist, creator, presenter, hands, body parts, silhouettes, reflections, testimonials, or human point-of-view.");
            generationRules.add("The product, packaging, ingredients, materials, environments, typography, motion graphics, sound design, and off-screen voiceover must carry the complete story.");
            generationRules.add("Every shotPlanner and imagePrompts entry must be product-only and explicitly exclude all humans and body parts.");
        }
        inputSnapshot.put("generationRules", generationRules);

        Map<String, Object> renderVariables = new LinkedHashMap<>(inputSnapshot);
        renderVariables.put("lockedBriefJson", toJson(sourceBrief));
        renderVariables.put("ideaAnglesJson", toJson(batchAngles));
        String renderedPrompt = promptTemplateService.render(template, renderVariables);
        renderedPrompt = appendNoHumanProductPrompt(renderedPrompt, noHumans, "idea");
        renderedPrompt = appendProductReferencePrompt(renderedPrompt, sourceBrief, "campaign idea");
        renderedPrompt = appendApprovedCreativeLearningPrompt(renderedPrompt, approvedCreativeLearnings);
        renderedPrompt = appendTrendContextPrompt(renderedPrompt, recentTrendSignals(sourceBrief));

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
        if (noHumans) {
            List<IdeaCandidate> productOnlyCandidates = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                productOnlyCandidates.add(enforceNoHumanProductIdeaCandidate(
                        lockedIdea,
                        sourceBrief,
                        existingCount + alreadyGeneratedCount + index + 1,
                        candidates.get(index)
                ));
            }
            candidates = productOnlyCandidates;
        }
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

    private List<String> ideaAnglesForBatch(Map<String, Object> sourceBrief, int offset, int count) {
        Map<String, Object> selectedCampaignAngle = mapValue(firstSelectionContextValue(sourceBrief, "campaignAngle"));
        if (!selectedCampaignAngle.isEmpty()) {
            String title = stringValue(selectedCampaignAngle.get("title"), "Selected campaign angle");
            String description = stringValue(selectedCampaignAngle.get("description"), "");
            String hook = stringValue(selectedCampaignAngle.get("hook"), "");
            String requiredAngle = "MANDATORY selected campaign angle: " + title
                    + (description.isBlank() ? "" : ". " + description)
                    + (hook.isBlank() ? "" : ". Hook: " + hook);
            return java.util.Collections.nCopies(Math.max(1, count), requiredAngle);
        }
        if (isProductAdBrief(sourceBrief)) {
            List<Map<String, String>> conceptLanes = isNoHumanProductAdBrief(sourceBrief)
                    ? NO_HUMAN_PRODUCT_AD_CONCEPT_LANES
                    : PRODUCT_AD_CONCEPT_LANES;
            List<String> productAngles = conceptLanes.stream()
                    .map(lane -> lane.get("title") + ": " + lane.get("description"))
                    .toList();
            List<String> angles = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                angles.add(productAngles.get((offset + index) % productAngles.size()));
            }
            return angles;
        }
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
        Map<String, Object> sourceBrief = buildSourceBrief(lockedIdea);
        context.put("sourceBrief", sourceBrief);
        context.putAll(productionStyleContextFromSelection(sourceBrief));
        copyIfPresent(sourceBrief, context, "briefMode");
        copyIfPresent(sourceBrief, context, "marketingAgentMode");
        copyIfPresent(sourceBrief, context, "productInputKey");
        copyIfPresent(sourceBrief, context, "productIntelligenceBrief");
        copyIfPresent(sourceBrief, context, "productUnderstanding");
        copyIfPresent(sourceBrief, context, "adConceptLanes");
        copyIfPresent(sourceBrief, context, "brandContext");
        copyIfPresent(sourceBrief, context, "campaignObjective");
        copyIfPresent(sourceBrief, context, "campaignAngle");
        context.put("hashtags", candidate.hashtags().isEmpty() ? hashtagsFor(lockedIdea, candidate.angle()) : candidate.hashtags());
        context.put("creativeNotes", candidate.creativeNotes());
        if (isProductAdBrief(sourceBrief)) {
            Map<String, Object> productBrief = new LinkedHashMap<>(productBriefFromSource(sourceBrief));
            Map<String, Object> creativeNotes = candidate.creativeNotes() == null ? Map.of() : candidate.creativeNotes();
            putIfPresent(productBrief, "adTone", firstValue(creativeNotes, "adTone", "tone"));
            putIfPresent(productBrief, "toneRationale", creativeNotes.get("toneRationale"));
            context.put("productIntelligenceBrief", productBrief);
        }
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
        putIfPresent(sourceBrief, "topicType", selectionContext.get("topicType"));
        putIfPresent(sourceBrief, "dialogueLanguage", selectionContext.get("dialogueLanguage"));
        putIfPresent(sourceBrief, "screenType", selectionContext.get("screenType"));
        putIfPresent(sourceBrief, "storytellingType", selectionContext.get("storytellingType"));
        putIfPresent(sourceBrief, "hookLens", selectionContext.get("hookLens"));
        putIfPresent(sourceBrief, "selectionPayload", selectionContext.get("selectionPayload"));
        putIfPresent(sourceBrief, "briefMode", firstSelectionContextValue(selectionContext, "briefMode"));
        putIfPresent(sourceBrief, "marketingAgentMode", firstSelectionContextValue(selectionContext, "marketingAgentMode"));
        putIfPresent(sourceBrief, "productInputKey", firstSelectionContextValue(selectionContext, "productInputKey"));
        putIfPresent(sourceBrief, "productIntelligenceBrief", firstSelectionContextValue(selectionContext, "productIntelligenceBrief"));
        putIfPresent(sourceBrief, "productUnderstanding", firstSelectionContextValue(selectionContext, "productUnderstanding"));
        putIfPresent(sourceBrief, "adConceptLanes", firstSelectionContextValue(selectionContext, "adConceptLanes"));
        putIfPresent(sourceBrief, "brandContext", firstSelectionContextValue(selectionContext, "brandContext"));
        putIfPresent(sourceBrief, "campaignObjective", firstSelectionContextValue(selectionContext, "campaignObjective"));
        putIfPresent(sourceBrief, "campaignAngle", firstSelectionContextValue(selectionContext, "campaignAngle"));
        sourceBrief.putAll(productionStyleContextFromSelection(selectionContext));
        putIfPresent(sourceBrief, "trend", selectionContext.get("trend"));
        return sourceBrief;
    }

    private Object firstSelectionContextValue(Map<String, Object> selectionContext, String key) {
        if (selectionContext == null || selectionContext.isEmpty()) {
            return null;
        }
        Object value = selectionContext.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> selectionPayload = mapValue(selectionContext.get("selectionPayload"));
        value = selectionPayload.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> ideaPayload = mapValue(selectionPayload.get("idea"));
        value = ideaPayload.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> productBrief = mapValue(selectionPayload.get("productIntelligenceBrief"));
        value = productBrief.get(key);
        return hasContextValue(value) ? value : null;
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
            copyIfPresent(rawIdea, creativeNotes, "adConceptLane");
            copyIfPresent(rawIdea, creativeNotes, "adConceptTitle");
            copyIfPresent(rawIdea, creativeNotes, "marketingObjective");
            copyIfPresent(rawIdea, creativeNotes, "cta");
            copyIfPresent(rawIdea, creativeNotes, "targetAudience");
            copyIfPresent(rawIdea, creativeNotes, "adTone");
            copyIfPresent(rawIdea, creativeNotes, "toneRationale");
            copyIfPresent(rawIdea, creativeNotes, "productUnderstanding");
            copyIfPresent(rawIdea, creativeNotes, "shotPlanner");
            copyIfPresent(rawIdea, creativeNotes, "imagePrompts");
            copyIfPresent(rawIdea, creativeNotes, "videoPromptPlan");
            copyIfPresent(rawIdea, creativeNotes, "voiceMusicCaptionPlan");
            copyIfPresent(rawIdea, creativeNotes, "editorPlan");
            copyIfPresent(rawIdea, creativeNotes, "productIntelligenceBrief");
            copyIfPresent(rawIdea, creativeNotes, "adConceptStrategy");
            copyIfPresent(rawIdea, creativeNotes, "campaignStrategy");
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
        Map<String, Object> sourceBrief = buildSourceBrief(lockedIdea);
        if (isProductAdBrief(sourceBrief)) {
            return fallbackProductAdIdeaCandidate(lockedIdea, sourceBrief, ideaNumber);
        }
        Map<String, Object> selectedCampaignAngle = mapValue(sourceBrief.get("campaignAngle"));
        String angle = !selectedCampaignAngle.isEmpty()
                ? stringValue(selectedCampaignAngle.get("title"), "Selected campaign angle")
                : IDEA_ANGLES.get((ideaNumber - 1) % IDEA_ANGLES.size());
        Map<String, Object> creativeNotes = new LinkedHashMap<>();
        creativeNotes.put("hook", stringValue(selectedCampaignAngle.get("hook"), angle));
        creativeNotes.put("targetEmotion", targetEmotionFor(angle));
        creativeNotes.put("storyShape", storyShapeFor(angle));
        if (!selectedCampaignAngle.isEmpty()) {
            creativeNotes.put("campaignAngle", selectedCampaignAngle);
            creativeNotes.put("selectionReason", "Fallback idea retained the selected campaign angle because the AI provider returned fewer candidates than requested.");
        }
        creativeNotes.putIfAbsent("selectionReason", "Fallback idea added because the AI provider returned fewer candidates than requested.");
        return new IdeaCandidate(
                buildTitle(lockedIdea, angle, ideaNumber),
                !selectedCampaignAngle.isEmpty()
                        ? truncate(stringValue(selectedCampaignAngle.get("description"), buildSummary(lockedIdea, angle)), 1000)
                        : buildSummary(lockedIdea, angle),
                hashtagsFor(lockedIdea, angle),
                creativeNotes
        );
    }

    private IdeaCandidate fallbackProductAdIdeaCandidate(CreatorIdea lockedIdea, Map<String, Object> sourceBrief, int ideaNumber) {
        boolean noHumans = isNoHumanProductAdBrief(sourceBrief);
        List<Map<String, String>> conceptLanes = noHumans
                ? NO_HUMAN_PRODUCT_AD_CONCEPT_LANES
                : PRODUCT_AD_CONCEPT_LANES;
        Map<String, String> lane = conceptLanes.get((ideaNumber - 1) % conceptLanes.size());
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        String productName = productDisplayName(productBrief, lockedIdea);
        Map<String, Object> strategy = productAdStrategyForLane(lane.get("key"), productBrief, productName, noHumans);
        Map<String, Object> selectedCampaignAngle = mapValue(sourceBrief.get("campaignAngle"));
        String conceptTitle = noHumans
                ? lane.get("title")
                : stringValue(selectedCampaignAngle.get("title"), lane.get("title"));
        Map<String, Object> creativeNotes = new LinkedHashMap<>();
        creativeNotes.put("briefMode", "product_ad_agent");
        creativeNotes.put("productInputKey", sourceBrief.get("productInputKey"));
        creativeNotes.put("productIntelligenceBrief", productBrief);
        creativeNotes.put("adConceptLane", lane.get("key"));
        creativeNotes.put("adConceptTitle", conceptTitle);
        creativeNotes.put("hook", stringValue(selectedCampaignAngle.get("hook"), stringValue(strategy.get("hook"))));
        creativeNotes.put("cta", strategy.get("cta"));
        creativeNotes.put("marketingObjective", strategy.get("marketingObjective"));
        creativeNotes.put("targetAudience", strategy.get("targetAudience"));
        putIfPresent(creativeNotes, "adTone", firstValue(productBrief, "adTone", "tone"));
        putIfPresent(creativeNotes, "toneRationale", productBrief.get("toneRationale"));
        creativeNotes.put("productUnderstanding", strategy.get("productUnderstanding"));
        creativeNotes.put("shotPlanner", strategy.get("shotPlanner"));
        creativeNotes.put("imagePrompts", strategy.get("imagePrompts"));
        creativeNotes.put("videoPromptPlan", strategy.get("videoPromptPlan"));
        creativeNotes.put("voiceMusicCaptionPlan", strategy.get("voiceMusicCaptionPlan"));
        creativeNotes.put("editorPlan", strategy.get("editorPlan"));
        creativeNotes.put("adConceptStrategy", strategy);
        creativeNotes.put("noHumans", noHumans);
        if (!selectedCampaignAngle.isEmpty()) {
            creativeNotes.put("campaignAngle", selectedCampaignAngle);
        }
        creativeNotes.put("selectionReason", !selectedCampaignAngle.isEmpty()
                ? "Fallback concept retained the selected campaign angle because the AI provider returned fewer candidates than requested."
                : strategy.get("selectionReason"));
        return new IdeaCandidate(
                truncate("%02d. %s: %s".formatted(ideaNumber, conceptTitle, productName), 240),
                truncate(noHumans
                        ? stringValue(strategy.get("summary"), lane.get("description"))
                        : stringValue(selectedCampaignAngle.get("description"), stringValue(strategy.get("summary"), lane.get("description"))), 1000),
                List.of("ProductAd", lane.get("key").replace("_", ""), "Commercial"),
                creativeNotes
        );
    }

    private boolean isProductAdBrief(Map<String, Object> sourceBrief) {
        if (sourceBrief == null || sourceBrief.isEmpty()) {
            return false;
        }
        String mode = stringValue(firstValue(sourceBrief, "briefMode", "mode"));
        if ("product_ad_agent".equalsIgnoreCase(mode)) {
            return true;
        }
        if (Boolean.TRUE.equals(sourceBrief.get("marketingAgentMode"))) {
            return true;
        }
        return !productBriefFromSource(sourceBrief).isEmpty();
    }

    private Map<String, Object> productBriefFromSource(Map<String, Object> sourceBrief) {
        if (sourceBrief == null || sourceBrief.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> productBrief = new LinkedHashMap<>(mapValue(sourceBrief.get("productIntelligenceBrief")));
        if (productBrief.isEmpty()) {
            Map<String, Object> selectionPayload = mapValue(sourceBrief.get("selectionPayload"));
            productBrief.putAll(mapValue(selectionPayload.get("productIntelligenceBrief")));
            if (productBrief.isEmpty()) {
                productBrief.putAll(mapValue(mapValue(selectionPayload.get("idea")).get("productIntelligenceBrief")));
            }
        }
        Map<String, Object> understanding = mapValue(firstValue(
                sourceBrief,
                "productUnderstanding"
        ));
        if (understanding.isEmpty()) {
            understanding = mapValue(productBrief.get("productUnderstanding"));
        }
        if (!understanding.isEmpty()) {
            productBrief.put("productUnderstanding", understanding);
        }
        putIfPresent(productBrief, "productInputKey", sourceBrief.get("productInputKey"));
        putIfPresent(productBrief, "campaignObjective", sourceBrief.get("campaignObjective"));
        return productBrief;
    }

    private List<Map<String, Object>> approvedCreativeLearningsFor(
            CreatorIdea idea,
            String categoryCode,
            Map<String, Object> sourceBrief
    ) {
        if (idea == null || !isProductAdBrief(sourceBrief)) {
            return List.of();
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        Map<String, Object> understanding = mapValue(productBrief.get("productUnderstanding"));
        Map<String, Object> direction = mapValue(productBrief.get("creativeDirection"));
        List<Map<String, Object>> guidance = creativeLearningService.approvedGuidance(
                idea.getTenantId(),
                idea.getUserId(),
                defaultString(categoryCode, stringValue(firstValue(sourceBrief, "categoryCode", "category"))),
                stringValue(firstNonNull(
                        direction.get("adFormat"),
                        direction.get("adFormatLabel"),
                        productBrief.get("adFormat"),
                        sourceBrief.get("adFormat")
                )),
                stringValue(firstNonNull(
                        understanding.get("productCategory"),
                        understanding.get("category"),
                        productBrief.get("productCategory"),
                        productBrief.get("category")
                ))
        );
        return guidance == null ? List.of() : guidance;
    }

    private String appendApprovedCreativeLearningPrompt(
            String renderedPrompt,
            List<Map<String, Object>> approvedCreativeLearnings
    ) {
        String enriched = creativeLearningService.appendApprovedGuidance(
                renderedPrompt,
                approvedCreativeLearnings
        );
        return enriched == null || enriched.isBlank() ? renderedPrompt : enriched;
    }

    void putProductReferenceAiContext(Map<String, Object> input, Map<String, Object> sourceBrief) {
        if (input == null || !isProductAdBrief(sourceBrief)) {
            return;
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        Map<String, Object> productUnderstanding = mapValue(productBrief.get("productUnderstanding"));
        List<String> imageUrls = productReferenceImageUrls(sourceBrief);
        input.put("referenceImageUrls", imageUrls);
        input.put("productReferenceImageUrls", imageUrls);
        input.put("attachReferenceImages", !imageUrls.isEmpty());
        input.put("ingredientDetails", truncate(stringValue(firstValue(
                productBrief,
                "ingredientDetails",
                "ingredients"
        ), stringValue(productUnderstanding.get("ingredients"))), 150));
        input.put("targetAudience", stringValue(firstValue(
                productBrief,
                "targetAudience"
        ), stringValue(productUnderstanding.get("targetAudience"))));
        input.put("campaignObjective", stringValue(firstValue(
                productBrief,
                "campaignObjective"
        )));
        input.put("adTone", stringValue(firstValue(
                productBrief,
                "adTone",
                "tone"
        ), stringValue(firstValue(productUnderstanding, "adTone", "tone"))));
        input.put("toneSelectionMode", "AUTO_FROM_PRODUCT_CONTEXT");
    }

    List<String> productReferenceImageUrls(Map<String, Object> sourceBrief) {
        if (!isProductAdBrief(sourceBrief)) {
            return List.of();
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        Map<String, Object> productUnderstanding = mapValue(productBrief.get("productUnderstanding"));
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addProductReferenceImageUrls(urls, productBrief.get("sourceProductImageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("imageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("productImageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, productUnderstanding.get("imageUrls"));
        for (Map<String, Object> asset : productReferenceImageAssets(sourceBrief)) {
            addProductReferenceImageUrls(urls, firstValue(
                    asset,
                    "assetUrl",
                    "signedUrl",
                    "publicUrl",
                    "imageUrl",
                    "url"
            ));
        }
        return urls.stream().limit(8).toList();
    }

    private void addProductReferenceImageUrls(LinkedHashSet<String> urls, Object value) {
        for (String url : stringList(value)) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                urls.add(url);
            }
        }
    }

    private List<Map<String, Object>> productReferenceImageAssets(Map<String, Object> sourceBrief) {
        if (!isProductAdBrief(sourceBrief)) {
            return List.of();
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        List<Map<String, Object>> assets = new ArrayList<>();
        Object[] sources = {
                productBrief.get("imageAssets"),
                productBrief.get("productImageAssets"),
                productBrief.get("referenceImageAssets")
        };
        for (Object source : sources) {
            for (Map<String, Object> asset : mapListValue(source)) {
                String bucket = stringValue(asset.get("bucket")).trim();
                String objectKey = stringValue(firstValue(asset, "objectKey", "object_key")).trim();
                String url = stringValue(firstValue(asset, "assetUrl", "signedUrl", "publicUrl", "imageUrl", "url")).trim();
                if ((bucket.isBlank() || objectKey.isBlank()) && url.isBlank()) {
                    continue;
                }
                boolean duplicate = assets.stream().anyMatch(existing ->
                        !bucket.isBlank()
                                && bucket.equals(stringValue(existing.get("bucket")).trim())
                                && objectKey.equals(stringValue(firstValue(existing, "objectKey", "object_key")).trim())
                                || !url.isBlank()
                                && url.equals(stringValue(firstValue(existing, "assetUrl", "signedUrl", "publicUrl", "imageUrl", "url")).trim()));
                if (!duplicate) {
                    Map<String, Object> canonical = new LinkedHashMap<>(asset);
                    canonical.put("referenceRole", "canonical_product_reference");
                    canonical.put("assetRole", "canonical_product_reference");
                    assets.add(canonical);
                }
                if (assets.size() >= 8) {
                    return assets;
                }
            }
        }
        return assets;
    }

    private String appendProductReferencePrompt(
            String renderedPrompt,
            Map<String, Object> sourceBrief,
            String stage
    ) {
        if (!isProductAdBrief(sourceBrief)) {
            return renderedPrompt;
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        Map<String, Object> productUnderstanding = mapValue(productBrief.get("productUnderstanding"));
        return defaultString(renderedPrompt, "") + """

                PRODUCT REFERENCE CONTRACT
                The supplied product images are attached to this request. Inspect all of them together as canonical visual evidence; preserve the exact package shape, label, logo, colors, materials, proportions, and visible ingredients across the %s.
                Ingredient/product details: %s
                Audience: %s
                Campaign objective: %s
                Treat the supplied audience and campaign objective as binding creative requirements, not passive metadata. Tailor the hook, narrative, proof beats, vocabulary, visual emphasis, retention devices, and CTA to them throughout the %s.
                Select the ad tone from those images and fields. Return adTone and toneRationale in the product/campaign metadata, then keep that tone consistent. If the objective is blank, infer the most commercially appropriate objective from the supplied product and audience. Never invent an ingredient, claim, certification, price, or package detail that is not supplied or visibly supported.
                """.formatted(
                stage,
                truncate(stringValue(firstValue(productBrief, "ingredientDetails", "ingredients"), stringValue(productUnderstanding.get("ingredients"))), 150),
                stringValue(firstValue(productBrief, "targetAudience"), stringValue(productUnderstanding.get("targetAudience"))),
                stringValue(firstValue(productBrief, "campaignObjective")),
                stage
        );
    }

    /**
     * Best-effort lookup of recently observed trend signals for this idea's category/platform/
     * country - populated by CreatorTrendConnectorScheduler -> SourceConnectorOrchestrationService
     * when the trend scheduler is enabled. Returns empty (not an error) when the scheduler is off,
     * the category is unset, or nothing has been observed yet - trend context is inspiration, never
     * a hard requirement, so idea generation must never depend on it existing.
     */
    private List<CreatorTrendSignal> recentTrendSignals(Map<String, Object> sourceBrief) {
        String category = stringValue(firstValue(sourceBrief, "categoryCode", "category"));
        if (category.isBlank()) {
            return List.of();
        }
        String platform = stringValue(sourceBrief.get("platformCode"));
        String country = stringValue(sourceBrief.get("countryCode"));
        try {
            return trendSignalRepository.findRecentSignals(
                    category,
                    platform.isBlank() ? null : platform,
                    country.isBlank() ? null : country,
                    PageRequest.of(0, 6)
            );
        } catch (RuntimeException ex) {
            log.warn("Trend signal lookup failed, continuing without trend context categoryCode={} errorType={} errorMessage={}",
                    category, ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private String appendTrendContextPrompt(String renderedPrompt, List<CreatorTrendSignal> signals) {
        if (signals.isEmpty()) {
            return renderedPrompt;
        }
        StringBuilder trendLines = new StringBuilder();
        for (CreatorTrendSignal signal : signals) {
            String title = truncate(defaultString(signal.getTitle(), ""), 140);
            if (title.isBlank()) {
                continue;
            }
            trendLines.append("- ").append(title);
            String summary = truncate(defaultString(signal.getSummary(), ""), 200);
            if (!summary.isBlank()) {
                trendLines.append(" - ").append(summary);
            }
            trendLines.append('\n');
        }
        if (trendLines.isEmpty()) {
            return renderedPrompt;
        }
        return defaultString(renderedPrompt, "") + """

                CURRENT TREND CONTEXT (optional signal, not a requirement)
                The following are recently observed trending topics for this category/platform. Use one only if it genuinely fits the brief - never force a trend reference that doesn't serve the idea, and never invent a trend not listed here.
                %s
                """.formatted(trendLines.toString().trim());
    }

    private void putProductReferencePersistence(
            Map<String, Object> scriptPayload,
            Map<String, Object> sourceBrief
    ) {
        if (scriptPayload == null || !isProductAdBrief(sourceBrief)) {
            return;
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        List<String> imageUrls = productReferenceImageUrls(sourceBrief);
        List<Map<String, Object>> imageAssets = productReferenceImageAssets(sourceBrief);
        scriptPayload.put("productIntelligenceBrief", productBrief);
        scriptPayload.put("productImageUrls", imageUrls);
        scriptPayload.put("referenceImageUrls", imageUrls);
        scriptPayload.put("productImageAssets", imageAssets);
        scriptPayload.put("referenceImageAssets", imageAssets);
        scriptPayload.put("ingredientDetails", firstValue(productBrief, "ingredientDetails", "ingredients"));
        scriptPayload.put("targetAudience", firstValue(
                productBrief,
                "targetAudience",
                "audience"
        ));
        scriptPayload.put("campaignObjective", productBrief.get("campaignObjective"));
        scriptPayload.put("adTone", firstValue(productBrief, "adTone", "tone"));
        scriptPayload.put("toneRationale", productBrief.get("toneRationale"));
    }

    boolean isNoHumanProductAdBrief(Map<String, Object> sourceBrief) {
        if (!isProductAdBrief(sourceBrief)) {
            return false;
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        Map<String, Object> creativeDirection = mapValue(productBrief.get("creativeDirection"));
        Object configured = firstValue(productBrief, "noHumans", "no_humans");
        if (configured == null) {
            configured = firstValue(creativeDirection, "noHumans", "no_humans");
        }
        if (configured == null) {
            configured = firstValue(sourceBrief, "noHumans", "no_humans");
        }
        return booleanValue(configured, false);
    }

    private IdeaCandidate enforceNoHumanProductIdeaCandidate(
            CreatorIdea lockedIdea,
            Map<String, Object> sourceBrief,
            int ideaNumber,
            IdeaCandidate providerCandidate
    ) {
        List<Map<String, String>> lanes = NO_HUMAN_PRODUCT_AD_CONCEPT_LANES;
        Map<String, Object> providerNotes = providerCandidate == null || providerCandidate.creativeNotes() == null
                ? Map.of()
                : providerCandidate.creativeNotes();
        String requestedLane = stringValue(providerNotes.get("adConceptLane"));
        Map<String, String> lane = lanes.stream()
                .filter(candidate -> candidate.get("key").equalsIgnoreCase(requestedLane))
                .findFirst()
                .orElse(lanes.get((Math.max(1, ideaNumber) - 1) % lanes.size()));
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        String productName = productDisplayName(productBrief, lockedIdea);
        Map<String, Object> strategy = productAdStrategyForLane(
                lane.get("key"),
                productBrief,
                productName,
                true
        );
        Map<String, Object> creativeNotes = new LinkedHashMap<>(providerNotes);
        creativeNotes.put("briefMode", "product_ad_agent");
        creativeNotes.put("productIntelligenceBrief", productBrief);
        creativeNotes.put("adConceptLane", lane.get("key"));
        creativeNotes.put("adConceptTitle", lane.get("title"));
        creativeNotes.put("marketingObjective", strategy.get("marketingObjective"));
        creativeNotes.put("hook", strategy.get("hook"));
        creativeNotes.put("cta", strategy.get("cta"));
        creativeNotes.put("targetAudience", strategy.get("targetAudience"));
        putIfPresent(creativeNotes, "adTone", firstValue(providerNotes, "adTone", "tone"));
        putIfPresent(creativeNotes, "toneRationale", providerNotes.get("toneRationale"));
        creativeNotes.put("productUnderstanding", strategy.get("productUnderstanding"));
        creativeNotes.put("shotPlanner", strategy.get("shotPlanner"));
        creativeNotes.put("imagePrompts", strategy.get("imagePrompts"));
        creativeNotes.put("videoPromptPlan", strategy.get("videoPromptPlan"));
        creativeNotes.put("voiceMusicCaptionPlan", strategy.get("voiceMusicCaptionPlan"));
        creativeNotes.put("editorPlan", strategy.get("editorPlan"));
        creativeNotes.put("adConceptStrategy", strategy);
        creativeNotes.put("noHumans", true);
        creativeNotes.put("humanExclusionLock", NO_HUMANS_NEGATIVE_PROMPT);
        creativeNotes.put("selectionReason", strategy.get("selectionReason"));
        return new IdeaCandidate(
                truncate("%02d. %s: %s".formatted(ideaNumber, lane.get("title"), productName), 240),
                truncate(stringValue(strategy.get("summary"), lane.get("description")), 1000),
                providerCandidate == null || providerCandidate.hashtags() == null || providerCandidate.hashtags().isEmpty()
                        ? List.of("ProductAd", "ProductOnly", "CGICommercial")
                        : providerCandidate.hashtags(),
                creativeNotes
        );
    }

    private String productDisplayName(Map<String, Object> productBrief, CreatorIdea lockedIdea) {
        Map<String, Object> understanding = mapValue(productBrief.get("productUnderstanding"));
        return truncate(defaultString(
                firstNonBlank(
                        stringValue(productBrief.get("displayName")),
                        stringValue(productBrief.get("productName")),
                        stringValue(understanding.get("productName")),
                        cleanBaseTitle(lockedIdea.getTitle())
                ),
                "Product campaign"
        ), 96);
    }

    private Map<String, Object> productAdStrategyForLane(
            String laneKey,
            Map<String, Object> productBrief,
            String productName,
            boolean noHumans
    ) {
        Map<String, Object> understanding = mapValue(productBrief.get("productUnderstanding"));
        String category = defaultString(stringValue(understanding.get("productCategory")), "product");
        String audience = defaultString(stringValue(understanding.get("targetAudience")), "likely buyers");
        String usp = defaultString(stringValue(understanding.get("usp")), "clear product value");
        String cta = defaultString(stringValue(productBrief.get("cta")), "Order today");
        if (noHumans) {
            return noHumanProductAdStrategyForLane(laneKey, productBrief, productName, category, audience, usp, cta);
        }
        List<String> imagePrompts = List.of(
                "Hero packshot of %s, clean product-first commercial image, brand colors respected, vertical 9:16 safe framing".formatted(productName),
                "Macro sensory detail for %s, premium lighting, shallow depth of field, high-retention social ad still".formatted(productName),
                "Lifestyle use moment for %s, product visible, believable environment, natural hands and practical composition".formatted(audience),
                "Final end-card style image with %s, clear CTA space, readable layout, no fake claims".formatted(productName)
        );
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("laneKey", laneKey);
        strategy.put("cta", cta);
        strategy.put("targetAudience", audience);
        strategy.put("productUnderstanding", understanding.isEmpty() ? productBrief : understanding);
        strategy.put("imagePrompts", imagePrompts);

        if ("luxury_brand_story".equals(laneKey)) {
            strategy.put("marketingObjective", "Increase premium perception and purchase confidence.");
            strategy.put("hook", productName + " should create desire and mystery before the complete pack or any ingredient explanation appears.");
            strategy.put("summary", "Position %s as a premium %s through a mystery-first brand world, artifact-like partial reveals, craft, ingredients, and an earned full-product hero.".formatted(productName, category));
            strategy.put("shotPlanner", List.of(
                    "0-2.5s brand-world hook: near-black, architectural scale, amber light, or abstract silhouette; no ingredients and no full pack",
                    "Artifact discovery: reveal only 10-20% through foil, edge geometry, engraving, material texture, or reflection",
                    "Distinct texture and craft transformation; do not repeat a pouring action",
                    "Ingredient reveal only after desire and product-world context are established",
                    "Earned full product hero with exact approved identity and clean CTA space"
            ));
            strategy.put("videoPromptPlan", List.of(
                    "4K master minimum; professional ARRI Alexa 35 / Sony Venice 2 class cinema capture, premium glass, 10/12-bit log or RAW intent, 24fps base and controlled 180-degree shutter",
                    "Professional motion-control dolly or calibrated tiny orbit from darkness; DP and gaffer-designed amber rim, shaped negative fill, modifiers and reflection control; below 10% product visibility",
                    "85-100mm cinema macro, remote focus with measured marks, one rack-focus event, gaffer-programmed light sweep, 10-20% partial reveal",
                    "Physically plausible texture or craft transition, one new visual idea, no repetitive pouring",
                    "Full approved pack appears only in the earned hero beat with stable logo, label, geometry, colors, claims, and proportions"
            ));
            strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Calm premium voiceover with minimal words.", "music", "Warm modern luxury bed ducked under speech.", "captions", "Sparse premium captions with key benefit words only."));
            strategy.put("editorPlan", "Plan every second at commercial-director, DP, gaffer, camera-operator, and focus-puller standard; never use rookie camera, lighting, exposure, or direction. Hold longer, use motivated transitions and restrained sound, and end on an earned full packshot.");
            strategy.put("selectionReason", "Best when brand perception and product desirability matter more than hard-selling.");
            return strategy;
        }

        if ("ugc_testimonial".equals(laneKey)) {
            strategy.put("marketingObjective", "Make the product feel discovered, useful, and socially believable.");
            strategy.put("hook", "I did not expect " + productName + " to be this useful.");
            strategy.put("summary", "Make %s feel like a creator recommendation with proof beats, quick reactions, and simple buyer language.".formatted(productName));
            strategy.put("shotPlanner", List.of("Creator discovery hook", "Product in hand or real-use setup", "Visible proof/detail moment", "Direct recommendation and CTA"));
            strategy.put("videoPromptPlan", List.of("Handheld creator-style movement, natural room light, honest framing", "Quick product insert with practical proof detail", "Fast social cut with testimonial caption emphasis"));
            strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Conversational first-person delivery.", "music", "Light social rhythm ducked below voice.", "captions", "Bold keyword captions on proof and CTA beats."));
            strategy.put("editorPlan", "Keep cuts fast, preserve natural pauses, add small whooshes sparingly, make proof beat impossible to miss.");
            strategy.put("selectionReason", "Best when the buyer needs authenticity and social proof before purchase.");
            return strategy;
        }

        strategy.put("marketingObjective", "Convert a clear buyer pain into purchase intent.");
        strategy.put("hook", "Still choosing ordinary " + category + "?");
        strategy.put("summary", "Open with a buyer problem, introduce %s as the cleaner solution, then close on %s and CTA.".formatted(productName, usp));
        strategy.put("shotPlanner", List.of("Problem visual in first three seconds", "Product enters as the solution", "Benefit proof through close-ups", "CTA packshot"));
        strategy.put("videoPromptPlan", List.of("Fast contrast cut from problem state to product reveal", "Cinematic product push-in with benefit text-safe framing", "Clean final packshot with motion accent and CTA space"));
        strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Direct response voiceover with clear benefit order.", "music", "Fast modern pulse ducked under dialogue.", "captions", "High-contrast captions for problem, solution, benefit, CTA."));
        strategy.put("editorPlan", "Use fast opening cuts, tighten every pause, add crisp transition accents, keep CTA readable.");
        strategy.put("selectionReason", "Best for immediate conversion and paid performance testing.");
        return strategy;
    }

    private Map<String, Object> noHumanProductAdStrategyForLane(
            String laneKey,
            Map<String, Object> productBrief,
            String productName,
            String category,
            String audience,
            String usp,
            String cta
    ) {
        Map<String, Object> understanding = mapValue(productBrief.get("productUnderstanding"));
        List<String> ingredients = stringList(firstValue(understanding, "ingredients", "keyIngredients", "materials"));
        List<String> benefits = stringList(firstValue(understanding, "benefits", "keyBenefits"));
        String evidence = firstNonBlank(
                ingredients.isEmpty() ? "" : String.join(", ", ingredients),
                benefits.isEmpty() ? "" : String.join(", ", benefits),
                usp
        );
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("laneKey", laneKey);
        strategy.put("cta", cta);
        strategy.put("targetAudience", audience);
        strategy.put("productUnderstanding", understanding.isEmpty() ? productBrief : understanding);
        strategy.put("noHumans", true);
        strategy.put("humanExclusionLock", NO_HUMANS_NEGATIVE_PROMPT);
        strategy.put("dialogueMode", "OFF_SCREEN_VOICEOVER");
        strategy.put("imagePrompts", List.of(
                "Canonical hero packshot of %s, exact packaging and brand identity, controlled commercial lighting, product only, %s".formatted(productName, NO_HUMANS_NEGATIVE_PROMPT),
                "Macro product texture and material detail for %s, premium sensory CGI, product only, %s".formatted(productName, NO_HUMANS_NEGATIVE_PROMPT),
                "Kinetic ingredient or feature composition around %s using %s, product remains canonical and unobstructed, %s".formatted(productName, evidence, NO_HUMANS_NEGATIVE_PROMPT),
                "Final product-only packshot of %s with clean CTA space, accurate packaging, no invented claims, %s".formatted(productName, NO_HUMANS_NEGATIVE_PROMPT)
        ));

        if ("luxury_brand_story".equals(laneKey)) {
            strategy.put("marketingObjective", "Increase premium perception and purchase confidence without relying on a lifestyle model.");
            strategy.put("hook", "Make " + productName + " feel mysterious and desirable before the first word, ingredient, or complete-pack reveal.");
            strategy.put("summary", "A product-only luxury film discovers %s as an artifact: brand world first, 10-20 percent detail reveals second, craft and ingredients later, then a precise earned hero packshot ending on %s.".formatted(productName, cta));
            strategy.put("shotPlanner", List.of(
                    "Near-black brand-world hook with abstract silhouette and less than 10% product visibility",
                    "Macro artifact discovery across real package foil, edge, engraving, or texture",
                    "Distinct texture and craft transformation with no pouring repetition",
                    "Ingredient or material proof after the desire hook",
                    "Canonical full hero packshot with CTA"
            ));
            strategy.put("videoPromptPlan", List.of(
                    "4K vertical or horizontal master minimum; ARRI Alexa 35 / Sony Venice 2 class cinema package, 10/12-bit log or RAW intent, professional lenses, remote focus and exposure discipline",
                    "Professional dolly or motion-control orbit; DP/gaffer-designed amber rim, negative fill, declared color temperature and contrast ratio; product only",
                    "100mm cinema macro slider, measured shallow-focus marks and rack focus, gaffer-programmed light sweep, 10-20% of approved identity visible; no hands",
                    "Physically plausible atmospheric CGI tied to approved product evidence and continuity",
                    "Locked earned hero with exact name, logo, packaging, colors, claims, proportions, and CTA space"
            ));
            strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Calm off-screen premium voiceover.", "music", "Restrained modern luxury bed.", "captions", "Sparse benefit captions; no on-camera speaker."));
            strategy.put("editorPlan", "Direct every second at professional commercial crew standard with controlled camera, focus, exposure, light, visibility, and sound; no rookie setup. Use motivated reveals, preserve canonical identity, and exclude all human forms.");
            strategy.put("selectionReason", "Best when premium perception must come entirely from product craft, light, texture, and sound.");
            return strategy;
        }

        if ("ingredient_transformation".equals(laneKey)) {
            strategy.put("marketingObjective", "Make product composition and benefits memorable through a product-safe CGI transformation.");
            strategy.put("hook", evidence + " becomes the visual trigger for " + productName + ".");
            strategy.put("summary", "A product-only CGI sequence transforms %s into the canonical %s pack, then demonstrates %s through macro motion and closes on %s.".formatted(evidence, productName, usp, cta));
            strategy.put("shotPlanner", List.of("Ingredient or material macro hook", "Kinetic assembly toward the product", "Benefit proof through product detail", "Canonical packshot and CTA"));
            strategy.put("videoPromptPlan", List.of("Macro ingredients or materials suspended in controlled space", "Kinetic CGI transformation assembling around the unchanged product", "Feature-focused orbit and texture reveal", "Clean product lockup with sound hit and CTA"));
            strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Bold off-screen product narration.", "music", "Rhythmic build with a clean reveal hit.", "captions", "Short evidence-led captions timed to transformations."));
            strategy.put("editorPlan", "Use match cuts, material transitions, and sync hits while preserving product geometry, label, and colors.");
            strategy.put("selectionReason", "Best for visually explaining ingredients, materials, or product benefits without a spokesperson.");
            return strategy;
        }

        strategy.put("marketingObjective", "Convert a buyer need into purchase intent using only product and environmental visual evidence.");
        strategy.put("hook", "Show the problem as a visual product-state contrast, then reveal " + productName + ".");
        strategy.put("summary", "A product-only problem-to-solution commercial contrasts an unsatisfying %s state with %s, proves %s through close product detail, and ends on %s.".formatted(category, productName, usp, cta));
        strategy.put("shotPlanner", List.of("Abstract or object-led problem state", "Product reveal as the solution", "Macro feature and benefit proof", "Canonical CTA packshot"));
        strategy.put("videoPromptPlan", List.of("Fast visual contrast using objects, typography, or environment only", "Cinematic product push-in with exact packaging", "Macro proof beat with product-safe CGI accents", "Locked final product frame with CTA space"));
        strategy.put("voiceMusicCaptionPlan", Map.of("voice", "Direct off-screen benefit voiceover.", "music", "Fast modern pulse under narration.", "captions", "Problem, solution, proof, and CTA captions."));
        strategy.put("editorPlan", "Use a fast opening contrast and crisp product reveals; never introduce customers, presenters, hands, or reflections.");
        strategy.put("selectionReason", "Best for direct response while maintaining a strict product-only visual language.");
        return strategy;
    }

    private String appendNoHumanProductPrompt(String renderedPrompt, boolean noHumans, String stage) {
        if (!noHumans) {
            return renderedPrompt;
        }
        String stageRules = switch (defaultString(stage, "").toLowerCase(Locale.ROOT)) {
            case "idea" -> """
                    - Every idea title, description, hook, shotPlanner entry, image prompt, and video prompt must describe a product-only commercial.
                    - Do not invent a named customer or make a human's craving, routine, reaction, testimonial, or decision the story.
                    """;
            case "story" -> """
                    - Return "characters": [] exactly. An empty character list is valid and required.
                    - The logline, conflict, storyline, hook, emotional arc, ending, and every beat must be carried by the product, packaging, ingredients/materials, environment, typography, motion graphics, sound, and off-screen voiceover.
                    - Do not use a named or unnamed protagonist, customer, creator, presenter, viewer, owner, family member, or human point-of-view.
                    """;
            case "screenplay" -> """
                    - Every shot must use peopleInFrame=0, primaryActors=[], sideActors=[], primaryCharacters=[], and sideCharacters=[].
                    - Use off-screen voiceOver only; dialogue must be an empty object and lip-sync is not required.
                    - Every visual prompt and shot must explicitly exclude people, faces, hands, arms, bodies, silhouettes, and human reflections.
                    """;
            default -> "";
        };
        return defaultString(renderedPrompt, "") + """

                HARD PRODUCT-ONLY / NO-HUMANS CONTRACT
                noHumans=true is a mandatory constraint, not a preference.
                - No people, faces, hands, arms, bodies, human silhouettes, reflections, presenters, customers, creators, actors, or human-operated use may appear or be implied.
                - Product, packaging, ingredients or materials, environment, kinetic typography, CGI motion, sound design, and off-screen narration must carry the story.
                - Never convert a UGC or lifestyle concept into a human testimonial. Convert its persuasion goal into product evidence.
                """
                + stageRules;
    }

    void applyNoHumanProductStoryContract(
            GeneratedStoryScriptResponse.StoryScript storyScript,
            CreatorIdea storyIdea,
            Map<String, Object> sourceBrief,
            int durationSeconds
    ) {
        if (storyScript == null) {
            return;
        }
        Map<String, Object> productBrief = productBriefFromSource(sourceBrief);
        String productName = productDisplayName(productBrief, storyIdea);
        Map<String, Object> selectionContext = storyIdea == null || storyIdea.getSelectionContext() == null
                ? Map.of()
                : storyIdea.getSelectionContext();
        Map<String, Object> creativeNotes = mapValue(selectionContext.get("creativeNotes"));
        int generatedIndex = integerValue(selectionContext.get("generatedIndex"), 1);
        String requestedLane = stringValue(creativeNotes.get("adConceptLane"));
        Map<String, String> lane = NO_HUMAN_PRODUCT_AD_CONCEPT_LANES.stream()
                .filter(candidate -> candidate.get("key").equalsIgnoreCase(requestedLane))
                .findFirst()
                .orElse(NO_HUMAN_PRODUCT_AD_CONCEPT_LANES.get((Math.max(1, generatedIndex) - 1) % NO_HUMAN_PRODUCT_AD_CONCEPT_LANES.size()));
        Map<String, Object> strategy = productAdStrategyForLane(
                lane.get("key"),
                productBrief,
                productName,
                true
        );
        Map<String, Object> understanding = mapValue(productBrief.get("productUnderstanding"));
        List<String> ingredients = stringList(firstValue(understanding, "ingredients", "keyIngredients", "materials"));
        List<String> benefits = stringList(firstValue(understanding, "benefits", "keyBenefits"));
        String usp = defaultString(stringValue(understanding.get("usp")), "the product's verified value");
        String evidence = firstNonBlank(
                ingredients.isEmpty() ? "" : String.join(", ", ingredients),
                benefits.isEmpty() ? "" : String.join(", ", benefits),
                usp
        );
        String cta = defaultString(stringValue(productBrief.get("cta")), "Order today");

        storyScript.setProjectTitle(productName + " - " + lane.get("title"));
        storyScript.setDuration(durationSeconds);
        storyScript.setNoHumans(true);
        storyScript.setNarrativeMode("product_only");
        storyScript.setDialogueMode("off_screen_voiceover");
        storyScript.setLogline(stringValue(
                strategy.get("summary"),
                "A product-only commercial turns material detail into a clear reveal of " + productName + "."
        ));
        storyScript.setCentralConflict(
                "Communicate desire and credible product value without relying on a customer, presenter, testimonial, hands, or any visible human."
        );
        storyScript.setStoryline(
                "Open with a product-relevant visual tension using objects, ingredients, materials, or typography. "
                        + "Reveal " + productName + " as the visual answer, build proof through " + evidence
                        + ", then resolve on the exact canonical product, a clean benefit message, and the CTA: " + cta + "."
        );
        storyScript.setEmotionalArc("Immediate curiosity -> sensory desire -> product proof -> purchase confidence");
        storyScript.setHook(defaultString(stringValue(strategy.get("hook")), "Open on an arresting product-only macro detail."));
        storyScript.setEndingPayoff(
                "The exact product holds in a clean hero frame while off-screen voiceover and typography deliver " + cta + "."
        );
        storyScript.setSetting(
                "A controlled product-CGI world built from packaging, ingredients or materials, brand colors, typography, and physically believable light; no human presence."
        );
        storyScript.setCharacters(List.of());
        storyScript.setBeats(noHumanProductStoryBeats(durationSeconds, productName, evidence));
    }

    void applyNoHumanProductStorySaveContract(GeneratedStoryScriptResponse.StoryScript storyScript) {
        if (storyScript == null) {
            return;
        }
        storyScript.setNoHumans(true);
        storyScript.setNarrativeMode("product_only");
        storyScript.setDialogueMode("off_screen_voiceover");
        storyScript.setCharacters(List.of());
    }

    private List<GeneratedStoryScriptResponse.StoryBeat> noHumanProductStoryBeats(
            int durationSeconds,
            String productName,
            String evidence
    ) {
        int hookSeconds = Math.max(2, Math.round(durationSeconds * 0.17f));
        int revealSeconds = Math.max(3, Math.round(durationSeconds * 0.25f));
        int proofSeconds = Math.max(3, Math.round(durationSeconds * 0.35f));
        int closeSeconds = Math.max(2, durationSeconds - hookSeconds - revealSeconds - proofSeconds);
        return List.of(
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(1)
                        .title("Product Tension Hook")
                        .summary("Use a macro material, ingredient, object-state contrast, or kinetic type beat to create immediate curiosity without showing a person.")
                        .characterFocus("Product, materials, and environment")
                        .emotionalPurpose("Stop the scroll with product-relevant visual tension.")
                        .estimatedSeconds(hookSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(2)
                        .title("Canonical Product Reveal")
                        .summary("Reveal " + productName + " with exact packaging, proportions, label, and brand colors as the answer to the opening tension.")
                        .characterFocus("Canonical product")
                        .emotionalPurpose("Create recognition and desire.")
                        .estimatedSeconds(revealSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(3)
                        .title("Sensory Proof")
                        .summary("Demonstrate " + evidence + " through product-safe macro detail, ingredients or materials, motion graphics, and off-screen voiceover.")
                        .characterFocus("Product evidence")
                        .emotionalPurpose("Turn visual desire into believable product value.")
                        .estimatedSeconds(proofSeconds)
                        .build(),
                GeneratedStoryScriptResponse.StoryBeat.builder()
                        .beatNumber(4)
                        .title("Hero Packshot Payoff")
                        .summary("Resolve on the unchanged canonical product with a clean benefit caption, sound hit, and readable CTA space.")
                        .characterFocus("Product and typography")
                        .emotionalPurpose("Land purchase confidence and recall.")
                        .estimatedSeconds(closeSeconds)
                        .build()
        );
    }

    void applyNoHumanProductScreenplayContract(GeneratedScriptResponse.CinematicScript screenplay) {
        if (screenplay == null) {
            return;
        }
        screenplay.putExtra("productLed", true);
        screenplay.putExtra("noHumans", true);
        screenplay.putExtra("narrativeMode", "product_only");
        screenplay.putExtra("dialogueMode", "off_screen_voiceover");
        screenplay.putExtra("lipSyncRequired", false);
        Map<String, Object> consistencyBible = new LinkedHashMap<>(
                screenplay.getVideoConsistencyBible() == null ? Map.of() : screenplay.getVideoConsistencyBible()
        );
        consistencyBible.put("noHumans", true);
        consistencyBible.put("humanExclusionLock", NO_HUMANS_NEGATIVE_PROMPT);
        consistencyBible.put("speakerVisibility", "OFF_SCREEN");
        screenplay.setVideoConsistencyBible(consistencyBible);
        Map<String, Object> shotMixPlan = new LinkedHashMap<>(
                screenplay.getShotMixPlan() == null ? Map.of() : screenplay.getShotMixPlan()
        );
        shotMixPlan.put("narratorFacePercent", 0);
        shotMixPlan.put("relatedVisualPercent", 100);
        shotMixPlan.put("recordOrGenerateVisualsNote", "Generate product-only CGI and product evidence shots; no human capture.");
        screenplay.setShotMixPlan(shotMixPlan);

        if (screenplay.getShots() == null) {
            return;
        }
        for (GeneratedScriptResponse.CinematicShot shot : screenplay.getShots()) {
            if (shot == null) {
                continue;
            }
            boolean replacedHumanBlocking = (shot.getPeopleInFrame() != null && shot.getPeopleInFrame() > 0)
                    || shot.getPrimaryActors() != null && !shot.getPrimaryActors().isEmpty()
                    || shot.getSideActors() != null && !shot.getSideActors().isEmpty()
                    || shot.getPrimaryCharacters() != null && !shot.getPrimaryCharacters().isEmpty()
                    || shot.getSideCharacters() != null && !shot.getSideCharacters().isEmpty();
            String dialogueVoiceOver = dialogueValuesText(shot.getDialogue());
            shot.setVoiceOver(firstNonBlank(shot.getVoiceOver(), dialogueVoiceOver));
            shot.setDialogue(Map.of());
            shot.setPeopleInFrame(0);
            shot.setPrimaryActors(List.of());
            shot.setSideActors(List.of());
            shot.setPrimaryCharacters(List.of());
            shot.setSideCharacters(List.of());
            shot.setPrimaryCharacterAction("No character is present; the product, materials, typography, and environment carry this beat.");
            shot.setPrimaryActorAction("No actor is required.");
            shot.setSideActorAction("No side actor or background person is allowed.");
            shot.setBlockingNotes("Product-only blocking. Keep all people and body parts outside the frame and out of reflections.");
            shot.setExpression("");
            shot.setBodyLanguage("");
            shot.setStorytellingRole("related_visual");
            shot.setGenerationMode("ai_generated");
            shot.setAssetCaptureMode("generate");
            if (replacedHumanBlocking) {
                String purpose = firstNonBlank(shot.getPurpose(), shot.getNarrativeBeat(), shot.getTitle(), "the planned story beat");
                shot.setAction("Express " + purpose + " using the canonical product, packaging, ingredients or materials, environment, typography, and CGI motion only.");
                shot.setSetDesign("Product-only CGI set derived from the planned environment and brand world; no people, body parts, silhouettes, or human reflections.");
            }
            shot.setAssetGenerationPrompt(appendNoHumanVisualExclusion(shot.getAssetGenerationPrompt()));
            shot.setSketchPrompt(appendNoHumanVisualExclusion(shot.getSketchPrompt()));
            shot.setSeedancePrompt(appendNoHumanVisualExclusion(shot.getSeedancePrompt()));
            shot.setCreatorDirection("Use only the product, objects, ingredients or materials, environment, typography, and off-screen voiceover. No human may appear.");
            shot.setDirectorNotes("Hard no-humans lock: " + NO_HUMANS_NEGATIVE_PROMPT + ".");
            shot.setCastReason("No cast is required for this product-only shot.");
            shot.putExtra("productLed", true);
            shot.putExtra("noHumans", true);
            shot.putExtra("negativePrompt", NO_HUMANS_NEGATIVE_PROMPT);
            shot.putExtra("speakerVisibility", "OFF_SCREEN");
            shot.putExtra("lipSyncRequired", false);
        }
    }

    private String appendNoHumanVisualExclusion(String prompt) {
        String base = defaultString(prompt, "Product-only commercial visual using the canonical product and its verified details.");
        if (base.toLowerCase(Locale.ROOT).contains("no people")
                && base.toLowerCase(Locale.ROOT).contains("no hands")) {
            return base;
        }
        return base + ". HARD EXCLUSION: " + NO_HUMANS_NEGATIVE_PROMPT + ".";
    }

    private String dialogueValuesText(Map<String, Object> dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return "";
        }
        return dialogue.values().stream()
                .map(this::dialogueValueText)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
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
            String screenType,
            String storytellingType,
            String hookLens
    ) {
        String title = cleanBaseTitle(storyIdea.getTitle());
        String normalizedStorytellingType = normalizeStorytellingType(storytellingType);
        String normalizedHookLens = normalizeHookLens(hookLens);
        List<GeneratedStoryScriptResponse.CharacterProfile> characters = characterProfilesFor(storyIdea, categoryCode, inferredTone, dialogueLanguage);
        return GeneratedStoryScriptResponse.StoryScript.builder()
                .projectTitle(title)
                .duration(durationSeconds)
                .category(categoryCode)
                .dialogueLanguage(dialogueLanguage)
                .screenType(screenType)
                .storytellingType(normalizedStorytellingType)
                .storytellingGuidance(storytellingGuidanceFor(normalizedStorytellingType))
                .hookLens(normalizedHookLens)
                .hookLensGuidance(hookLensGuidanceFor(normalizedHookLens))
                .hookBridge(defaultHookBridgeFor(normalizedHookLens))
                .factualityNotes(defaultFactualityNotesFor(normalizedHookLens))
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
            String storytellingType,
            Map<String, Object> storytellingGuidance,
            String hookLens,
            Map<String, Object> hookLensGuidance,
            boolean noHumans,
            Map<String, Object> diagnostics
    ) {
        GeneratedStoryScriptResponse.StoryScript fallback = buildStoryScriptPayload(
                storyIdea,
                durationSeconds,
                categoryCode,
                inferredTone,
                dialogueLanguage,
                screenType,
                storytellingType,
                hookLens
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
                String validationReason = storyScriptValidationReason(storyScript, noHumans);
                if (validationReason.isBlank()) {
                    applyStoryScriptDefaults(storyScript, fallback, storyIdea, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, storytellingType, storytellingGuidance, hookLens, hookLensGuidance, noHumans);
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
        return storyScriptValidationReason(storyScript, false).isBlank();
    }

    private String storyScriptValidationReason(
            GeneratedStoryScriptResponse.StoryScript storyScript,
            boolean noHumans
    ) {
        if (storyScript == null) {
            return "NULL_STORY_SCRIPT";
        }
        boolean hasStory = !defaultString(storyScript.getProjectTitle(), "").isBlank()
                || !defaultString(storyScript.getLogline(), "").isBlank()
                || !defaultString(storyScript.getStoryline(), "").isBlank();
        boolean hasStructure = storyScript.getBeats() != null && !storyScript.getBeats().isEmpty()
                || !noHumans && storyScript.getCharacters() != null && !storyScript.getCharacters().isEmpty();
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
            String screenType,
            String storytellingType,
            Map<String, Object> storytellingGuidance,
            String hookLens,
            Map<String, Object> hookLensGuidance,
            boolean noHumans
    ) {
        storyScript.setProjectTitle(defaultString(storyScript.getProjectTitle(), fallback.getProjectTitle()));
        storyScript.setDuration(storyScript.getDuration() == null || storyScript.getDuration() <= 0 ? durationSeconds : storyScript.getDuration());
        storyScript.setCategory(defaultString(storyScript.getCategory(), categoryCode));
        storyScript.setDialogueLanguage(defaultString(storyScript.getDialogueLanguage(), dialogueLanguage));
        storyScript.setScreenType(normalizeScreenType(defaultString(storyScript.getScreenType(), screenType)));
        String normalizedStorytellingType = normalizeStorytellingType(defaultString(storyScript.getStorytellingType(), storytellingType));
        storyScript.setStorytellingType(normalizedStorytellingType);
        storyScript.setStorytellingGuidance(nonEmptyMap(storyScript.getStorytellingGuidance(), nonEmptyMap(storytellingGuidance, fallback.getStorytellingGuidance())));
        String normalizedHookLens = normalizeHookLens(defaultString(storyScript.getHookLens(), hookLens));
        storyScript.setHookLens(normalizedHookLens);
        storyScript.setHookLensGuidance(nonEmptyMap(storyScript.getHookLensGuidance(), nonEmptyMap(hookLensGuidance, fallback.getHookLensGuidance())));
        storyScript.setHookBridge(nonEmptyMap(storyScript.getHookBridge(), nonEmptyMap(fallback.getHookBridge(), defaultHookBridgeFor(normalizedHookLens))));
        storyScript.setFactualityNotes(nonEmptyMap(storyScript.getFactualityNotes(), nonEmptyMap(fallback.getFactualityNotes(), defaultFactualityNotesFor(normalizedHookLens))));
        storyScript.setLogline(defaultString(storyScript.getLogline(), fallback.getLogline()));
        storyScript.setCentralConflict(defaultString(storyScript.getCentralConflict(), fallback.getCentralConflict()));
        storyScript.setStoryline(defaultString(storyScript.getStoryline(), fallback.getStoryline()));
        storyScript.setEmotionalArc(defaultString(storyScript.getEmotionalArc(), fallback.getEmotionalArc()));
        storyScript.setHook(defaultString(storyScript.getHook(), fallback.getHook()));
        storyScript.setEndingPayoff(defaultString(storyScript.getEndingPayoff(), fallback.getEndingPayoff()));
        storyScript.setSetting(defaultString(storyScript.getSetting(), fallback.getSetting()));
        storyScript.setInferredTone(defaultString(storyScript.getInferredTone(), inferredTone));
        if (!noHumans && (storyScript.getCharacters() == null || storyScript.getCharacters().isEmpty())) {
            storyScript.setCharacters(fallback.getCharacters());
        }
        if (storyScript.getBeats() == null || storyScript.getBeats().isEmpty()) {
            storyScript.setBeats(noHumans
                    ? noHumanProductStoryBeats(durationSeconds, fallback.getProjectTitle(), "product proof")
                    : storyBeatsFor(durationSeconds, storyScript.getCharacters()));
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
            String storytellingType,
            Map<String, Object> storytellingGuidance,
            String hookLens,
            Map<String, Object> hookLensGuidance,
            Map<String, Object> diagnostics
    ) {
        GeneratedScriptResponse.CinematicScript fallback = buildCinematicScriptPayload(
                storyIdea,
                storyScript,
                durationSeconds,
                categoryCode,
                inferredTone,
                dialogueLanguage,
                screenType,
                storytellingType,
                storytellingGuidance,
                hookLens,
                hookLensGuidance
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
                    applyCinematicScriptDefaults(scriptPayload, fallback, durationSeconds, categoryCode, inferredTone, dialogueLanguage, screenType, storytellingType, storytellingGuidance, hookLens, hookLensGuidance);
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
            String screenType,
            String storytellingType,
            Map<String, Object> storytellingGuidance,
            String hookLens,
            Map<String, Object> hookLensGuidance
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
        String normalizedStorytellingType = normalizeStorytellingType(defaultString(scriptPayload.getStorytellingType(), storytellingType));
        scriptPayload.setStorytellingType(normalizedStorytellingType);
        scriptPayload.setStorytellingGuidance(nonEmptyMap(scriptPayload.getStorytellingGuidance(), nonEmptyMap(storytellingGuidance, fallback.getStorytellingGuidance())));
        String normalizedHookLens = normalizeHookLens(defaultString(scriptPayload.getHookLens(), hookLens));
        scriptPayload.setHookLens(normalizedHookLens);
        scriptPayload.setHookLensGuidance(nonEmptyMap(scriptPayload.getHookLensGuidance(), nonEmptyMap(hookLensGuidance, fallback.getHookLensGuidance())));
        scriptPayload.setHookBridge(nonEmptyMap(scriptPayload.getHookBridge(), nonEmptyMap(fallback.getHookBridge(), defaultHookBridgeFor(normalizedHookLens))));
        scriptPayload.setFactualityNotes(nonEmptyMap(scriptPayload.getFactualityNotes(), nonEmptyMap(fallback.getFactualityNotes(), defaultFactualityNotesFor(normalizedHookLens))));
        scriptPayload.setShotMixPlan(nonEmptyMap(scriptPayload.getShotMixPlan(), fallback.getShotMixPlan()));
        applyShotStorytellingDefaults(scriptPayload.getShots(), normalizedStorytellingType);
    }

    private void applyShotStorytellingDefaults(List<GeneratedScriptResponse.CinematicShot> shots, String storytellingType) {
        if (shots == null || shots.isEmpty()) {
            return;
        }
        for (int index = 0; index < shots.size(); index++) {
            GeneratedScriptResponse.CinematicShot shot = shots.get(index);
            if (shot == null) {
                continue;
            }
            int shotNumber = shot.getShotNumber() == null || shot.getShotNumber() <= 0 ? index + 1 : shot.getShotNumber();
            String role = defaultString(shot.getStorytellingRole(), storytellingRoleFor(shotNumber, storytellingType));
            shot.setStorytellingRole(role);
            shot.setAssetCaptureMode(defaultString(shot.getAssetCaptureMode(), assetCaptureModeFor(role)));
            shot.setAssetGenerationPrompt(defaultString(
                    shot.getAssetGenerationPrompt(),
                    assetGenerationPromptFor(role, defaultString(shot.getTitle(), "creator story"), shotPhase(shotNumber, shots.size()), defaultString(shot.getAction(), ""), "vertical")
            ));
            if ("related_visual".equals(role)) {
                if (shot.getDialogue() == null) {
                    shot.setDialogue(Map.of());
                }
                if (shot.getPrimaryActors() == null) {
                    shot.setPrimaryActors(List.of());
                }
                if (shot.getSideActors() == null) {
                    shot.setSideActors(List.of());
                }
                if (shot.getPeopleInFrame() == null) {
                    shot.setPeopleInFrame(0);
                }
            }
        }
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
        payload.remove("rawTextPreview");
        payload.remove("rawTextLength");
        payload.remove("responseStatus");
        payload.remove("configuredMaxOutputTokens");
        payload.remove("timeoutMs");
        payload.remove("incompleteDetails");
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
                "storytellingType",
                "storytellingGuidance",
                "hookLens",
                "hookLensGuidance",
                "hookBridge",
                "factualityNotes",
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
            String screenType,
            String storytellingType,
            String hookLens
    ) {
        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("storyScript", toStoryScriptMap(storyScript));
        context.put("storyScriptGeneratedAt", OffsetDateTime.now().toString());
        context.put("storyScriptPromptRunId", promptRunId == null ? null : promptRunId.toString());
        context.put("storyScriptGenerationJobId", generationJobId == null ? null : generationJobId.toString());
        context.put("storyScriptDurationSeconds", durationSeconds);
        context.put("storyScriptDialogueLanguage", dialogueLanguage);
        context.put("storyScriptScreenType", screenType);
        context.put("storyScriptStorytellingType", storytellingType);
        context.put("storyScriptHookLens", hookLens);

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
        builder.append("Storytelling Type: ").append(defaultString(storyScript.getStorytellingType(), "narrator_visual_mix")).append("\n\n");
        builder.append("Hook Lens: ").append(defaultString(storyScript.getHookLens(), "direct")).append("\n");
        builder.append("Hook Bridge: ").append(toJson(storyScript.getHookBridge() == null ? Map.of() : storyScript.getHookBridge())).append("\n\n");
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
            String screenType,
            String storytellingType,
            Map<String, Object> storytellingGuidance,
            String hookLens,
            Map<String, Object> hookLensGuidance
    ) {
        int totalShots = shotCountForDuration(durationSeconds);
        String normalizedStorytellingType = normalizeStorytellingType(storytellingType);
        String normalizedHookLens = normalizeHookLens(defaultString(hookLens, storyScript == null ? null : storyScript.getHookLens()));
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
            shots.add(buildCinematicShot(storyIdea, categoryCode, inferredTone, dialogueLanguage, screenType, normalizedStorytellingType, characters, index + 1, start, end, totalShots));
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
                .storytellingType(normalizedStorytellingType)
                .storytellingGuidance(nonEmptyMap(storytellingGuidance, storytellingGuidanceFor(normalizedStorytellingType)))
                .hookLens(normalizedHookLens)
                .hookLensGuidance(nonEmptyMap(hookLensGuidance, nonEmptyMap(storyScript == null ? null : storyScript.getHookLensGuidance(), hookLensGuidanceFor(normalizedHookLens))))
                .hookBridge(nonEmptyMap(storyScript == null ? null : storyScript.getHookBridge(), defaultHookBridgeFor(normalizedHookLens)))
                .factualityNotes(nonEmptyMap(storyScript == null ? null : storyScript.getFactualityNotes(), defaultFactualityNotesFor(normalizedHookLens)))
                .shotMixPlan(shotMixPlanFor(normalizedStorytellingType))
                .shots(shots)
                .build();
    }

    private GeneratedScriptResponse.CinematicShot buildCinematicShot(
            CreatorIdea storyIdea,
            String categoryCode,
            String inferredTone,
            String dialogueLanguage,
            String screenType,
            String storytellingType,
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
        String storytellingRole = storytellingRoleFor(shotNumber, storytellingType);
        boolean relatedVisual = "related_visual".equals(storytellingRole);

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
                .peopleInFrame(relatedVisual ? 0 : peopleInFrameFor(phase))
                .primaryActors(relatedVisual ? List.of() : primaryActorsFor(phase, dialogueLanguage, characters))
                .sideActors(relatedVisual ? List.of() : sideActorsFor(phase, inferredTone, dialogueLanguage, characters))
                .primaryActorAction(relatedVisual ? "No actor required; show the related visual clearly." : primaryActorActionFor(phase, title))
                .sideActorAction(relatedVisual ? "No side actor required in this shot." : sideActorActionFor(phase, inferredTone))
                .expression(expression)
                .emotion(emotionFor(phase, inferredTone))
                .bodyLanguage(bodyLanguageFor(phase))
                .lighting("Natural window light or soft outdoor shade; avoid harsh overhead light")
                .environment(environmentFor(categoryCode))
                .action(action)
                .voiceOver(voiceOverFor(phase, dialogueLanguage))
                .dialogue(relatedVisual ? Map.of() : dialogueMapForPhase(phase, dialogueLanguage))
                .textOverlay(textOverlayFor(phase, dialogueLanguage))
                .transition(transitionFor(phase))
                .storytellingRole(storytellingRole)
                .assetCaptureMode(assetCaptureModeFor(storytellingRole))
                .assetGenerationPrompt(assetGenerationPromptFor(storytellingRole, title, phase, action, screenType))
                .soundDesign(new ArrayList<Object>(soundDesignFor(phase)))
                .editingNotes(new ArrayList<Object>(editingNotesFor(phase)))
                .retentionGoal(retentionGoalFor(phase))
                .creatorDirection(relatedVisual ? "Use this as recordable B-roll or generate it from the asset prompt." : creatorDirectionFor(phase))
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
            builder.append("Story Role: ").append(defaultString(scene.getStorytellingRole(), "")).append("\n");
            builder.append("Asset Mode: ").append(defaultString(scene.getAssetCaptureMode(), "")).append("\n");
            builder.append("Asset Prompt: ").append(defaultString(scene.getAssetGenerationPrompt(), "")).append("\n");
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

    private void enrichVideoGenerationPlan(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            String categoryCode,
            String screenType,
            String storytellingType
    ) {
        if (scriptPayload == null) {
            return;
        }
        List<GeneratedScriptResponse.CinematicShot> shots = scriptPayload.getShots();
        if (shots == null || shots.isEmpty()) {
            return;
        }
        String pacingKey = videoPacingKey(scriptPayload, categoryCode, storytellingType);
        String productionStyle = normalizeProductionStyle(scriptPayload.getProductionStyle());
        Map<String, Object> pacingProfile = videoPacingProfile(scriptPayload, shots, pacingKey);
        scriptPayload.setVideoPacingProfile(mergeDefaults(scriptPayload.getVideoPacingProfile(), pacingProfile));

        Map<String, Object> consistencyBible = videoConsistencyBible(scriptPayload, shots, categoryCode, screenType);
        scriptPayload.setVideoConsistencyBible(mergeDefaults(scriptPayload.getVideoConsistencyBible(), consistencyBible));

        String globalPrompt = seedanceGlobalPrompt(pacingKey, screenType, scriptPayload.getVideoConsistencyBible());
        Map<String, Object> promptStrategy = new LinkedHashMap<>();
        promptStrategy.put("provider", "seedance");
        promptStrategy.put("maxClipSeconds", 15);
        promptStrategy.put("pacingKey", pacingKey);
        promptStrategy.put("productionStyle", productionStyle);
        promptStrategy.put("sceneGenerationRule", "full_ai".equals(productionStyle)
                ? "Every scene is generated by Seedance. Do not require uploaded/recorded talking-head footage."
                : "Hybrid scenes may use user-recorded talking-head clips or Seedance-generated AI clips according to generationMode.");
        promptStrategy.put("globalConsistencyPrompt", globalPrompt);
        promptStrategy.put("fastPacedPrompt", "Use energetic cuts, visible motion, punchy camera movement, strong hook text, and short readable caption beats. Keep every shot visually coherent with the locked character, wardrobe, set, lighting, and color palette.");
        promptStrategy.put("slowPacedPrompt", "Use steadier camera language, longer emotional beats, softer motion, fewer cuts, and more breathing room. Keep every shot visually coherent with the locked character, wardrobe, set, lighting, and color palette.");
        promptStrategy.put("continuityTechniques", List.of(
                "repeat locked character identity words in every scene prompt",
                "carry wardrobe, hair, face, props, and set geography across adjacent shots",
                "include previous-shot and next-shot continuity notes",
                "preserve screen direction, eyeline, lighting temperature, lens language, and aspect ratio",
                "use the first generated frame or approved storyboard image as a reference frame when the provider supports it",
                "use negative prompts that forbid face drift, wardrobe changes, extra fingers, logo artifacts, and sudden environment changes"
        ));
        promptStrategy.put("srtRequired", true);
        scriptPayload.setSeedancePromptStrategy(mergeDefaults(scriptPayload.getSeedancePromptStrategy(), promptStrategy));

        List<Map<String, Object>> allCues = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            GeneratedScriptResponse.CinematicShot shot = shots.get(index);
            if (shot == null) {
                continue;
            }
            int shotNumber = shot.getShotNumber() == null || shot.getShotNumber() <= 0 ? index + 1 : shot.getShotNumber();
            double start = secondsValue(shot.getStartTime(), timelineStartFallback(shots, index));
            double end = secondsValue(shot.getEndTime(), start + durationForShot(shot));
            if (end <= start) {
                end = start + Math.max(1d, durationForShot(shot));
            }
            shot.setDurationSeconds(Math.max(0.5d, end - start));

            List<Map<String, Object>> captionTrack = normalizedCaptionTrackForShot(shot, start, end);
            if (shot.getCaptionTrack() == null || shot.getCaptionTrack().isEmpty()) {
                shot.setCaptionTrack(captionTrack);
            }
            List<Map<String, Object>> srtCues = srtCuesForShot(shotNumber, captionTrack, start, end);
            shot.setSrtCues(srtCues);
            allCues.addAll(srtCues);

            Map<String, Object> continuity = shotVideoContinuity(shot, shotNumber, shots, index, scriptPayload.getVideoConsistencyBible());
            shot.setVideoContinuity(mergeDefaults(shot.getVideoContinuity(), continuity));
            shot.setPacingPrompt(defaultString(shot.getPacingPrompt(), shotPacingPrompt(pacingKey, shot)));
            shot.setSeedancePrompt(defaultString(
                    shot.getSeedancePrompt(),
                    seedanceScenePrompt(scriptPayload, shot, shotNumber, pacingKey, globalPrompt)
            ));
        }

        List<Map<String, Object>> normalizedCues = normalizeSrtCueSequence(allCues);
        String srt = buildSrt(normalizedCues);
        scriptPayload.setSrtCues(normalizedCues);
        scriptPayload.setSrt(srt);
        Map<String, Object> srtFile = new LinkedHashMap<>();
        srtFile.put("filename", "generated.srt");
        srtFile.put("contentType", "application/x-subrip");
        srtFile.put("cueCount", normalizedCues.size());
        srtFile.put("durationSeconds", scriptPayload.getDuration());
        srtFile.put("content", srt);
        scriptPayload.setSrtFile(mergeDefaults(scriptPayload.getSrtFile(), srtFile));
    }

    private Map<String, Object> videoPacingProfile(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            List<GeneratedScriptResponse.CinematicShot> shots,
            String pacingKey
    ) {
        int duration = scriptPayload == null || scriptPayload.getDuration() == null ? 0 : scriptPayload.getDuration();
        int shotCount = shots == null || shots.isEmpty() ? 1 : shots.size();
        double averageShotSeconds = duration > 0 ? duration / (double) shotCount : 0d;
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("paceKey", pacingKey);
        profile.put("averageShotSeconds", Math.round(averageShotSeconds * 100d) / 100d);
        profile.put("cutDensity", switch (pacingKey) {
            case "fast_paced" -> "high";
            case "slow_paced" -> "low";
            default -> "medium";
        });
        profile.put("captionRhythm", switch (pacingKey) {
            case "fast_paced" -> "short punchy captions every 1.2-2.4 seconds";
            case "slow_paced" -> "readable captions every 2.8-5.0 seconds with emotional breathing room";
            default -> "clean captions every 2.0-3.5 seconds";
        });
        profile.put("shotDurationRule", switch (pacingKey) {
            case "fast_paced" -> "Prefer 1.5-3.5 second shots, except payoff shots may breathe briefly.";
            case "slow_paced" -> "Prefer 4-8 second shots with steadier camera and fewer abrupt transitions.";
            default -> "Mix 2.5-5 second shots with faster hook and slower payoff.";
        });
        return profile;
    }

    private String videoPacingKey(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            String categoryCode,
            String storytellingType
    ) {
        String text = (
                defaultString(categoryCode, "")
                        + " " + defaultString(storytellingType, "")
                        + " " + defaultString(scriptPayload == null ? null : scriptPayload.getPacingStyle(), "")
                        + " " + defaultString(scriptPayload == null ? null : scriptPayload.getInferredTone(), "")
        ).toLowerCase(Locale.ROOT);
        int duration = scriptPayload == null || scriptPayload.getDuration() == null ? 0 : scriptPayload.getDuration();
        if (text.contains("comedy") || text.contains("meme") || text.contains("trend") || text.contains("fitness")
                || text.contains("urgent") || text.contains("fast") || duration > 0 && duration <= 45) {
            return "fast_paced";
        }
        if (text.contains("emotional") || text.contains("romantic") || text.contains("documentary")
                || text.contains("slow") || text.contains("dramatic_scene") || duration >= 180) {
            return "slow_paced";
        }
        return "balanced";
    }

    private Map<String, Object> videoConsistencyBible(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            List<GeneratedScriptResponse.CinematicShot> shots,
            String categoryCode,
            String screenType
    ) {
        Map<String, Object> bible = new LinkedHashMap<>();
        bible.put("projectTitle", defaultString(scriptPayload == null ? null : scriptPayload.getProjectTitle(), "Creator video"));
        bible.put("screenType", normalizeScreenType(defaultString(screenType, scriptPayload == null ? null : scriptPayload.getScreenType())));
        bible.put("category", defaultString(categoryCode, scriptPayload == null ? null : scriptPayload.getCategory()));
        bible.put("characterIdentityLocks", collectUniqueShotValues(shots, "actors"));
        bible.put("wardrobeAndAppearanceLocks", collectUniqueShotValues(shots, "appearance"));
        bible.put("setAndPropLocks", collectUniqueShotValues(shots, "set"));
        bible.put("cameraLanguageLocks", collectUniqueShotValues(shots, "camera"));
        bible.put("lightingAndColorLocks", collectUniqueShotValues(shots, "lighting"));
        bible.put("continuityRules", List.of(
                "Do not change the main character face, age, hairstyle, wardrobe, body type, or skin tone between shots.",
                "Keep location geography and props stable unless the screenplay says the scene changes.",
                "Preserve left-right screen direction, eyeline, lens feel, lighting temperature, and color palette across adjacent clips.",
                "Use captions inside safe zones and keep space for platform UI.",
                "When a shot is regenerated after chat, apply only that requested change and preserve the locked continuity bible."
        ));
        bible.put("negativePrompt", "Do not introduce a new actor, changed face, changed outfit, changed room layout, wrong aspect ratio, unreadable text, extra limbs, logo artifacts, watermark, random subtitles, or inconsistent lighting.");
        return bible;
    }

    private List<String> collectUniqueShotValues(List<GeneratedScriptResponse.CinematicShot> shots, String kind) {
        List<String> values = new ArrayList<>();
        for (GeneratedScriptResponse.CinematicShot shot : shots == null ? List.<GeneratedScriptResponse.CinematicShot>of() : shots) {
            if (shot == null) {
                continue;
            }
            List<String> candidates = switch (kind) {
                case "actors" -> List.of(
                        listText(shot.getPrimaryActors()),
                        listText(shot.getSideActors()),
                        defaultString(shot.getExpression(), ""),
                        defaultString(shot.getBodyLanguage(), "")
                );
                case "appearance" -> List.of(
                        defaultString(shot.getBlockingNotes(), ""),
                        defaultString(shot.getCreatorDirection(), ""),
                        defaultString(shot.getPrimaryActorAction(), "")
                );
                case "set" -> List.of(
                        defaultString(shot.getSetDesign(), ""),
                        defaultString(shot.getEnvironment(), ""),
                        stringValue(shot.getResourceRequirements())
                );
                case "camera" -> List.of(
                        defaultString(shot.getShotType(), ""),
                        defaultString(shot.getCameraAngle(), ""),
                        defaultString(shot.getCameraMovement(), ""),
                        defaultString(shot.getLensSuggestion(), "")
                );
                case "lighting" -> List.of(
                        defaultString(shot.getLighting(), ""),
                        defaultString(shot.getLightingMobile(), ""),
                        defaultString(shot.getLightingProfessional(), "")
                );
                default -> List.of();
            };
            for (String candidate : candidates) {
                String cleaned = truncate(candidate == null ? "" : candidate.trim(), 160);
                if (!cleaned.isBlank() && !values.contains(cleaned)) {
                    values.add(cleaned);
                }
                if (values.size() >= 8) {
                    return values;
                }
            }
        }
        return values;
    }

    private String seedanceGlobalPrompt(String pacingKey, String screenType, Map<String, Object> consistencyBible) {
        String pacingLine = switch (pacingKey) {
            case "fast_paced" -> "Fast paced: punchy movement, quick visual payoff, crisp readable captions, energetic but coherent edits.";
            case "slow_paced" -> "Slow paced: steadier movement, expressive pauses, smoother transitions, emotional readability before cutting.";
            default -> "Balanced pacing: fast hook, readable middle, clear payoff, no rushed emotional beats.";
        };
        return """
                Generate a coherent short-form video scene using the screenplay JSON as the source of truth.
                %s
                Maintain continuity across all clips: same character identity, face, wardrobe, hairstyle, props, set geography, lighting temperature, color palette, camera/lens language, screen direction, and aspect ratio.
                Use reference frames or approved storyboard frames when available. If regenerating one scene, change only the requested detail and preserve all other continuity locks.
                Keep captions and text overlays inside mobile safe zones. Do not create random subtitles; use the provided SRT/caption cues.
                Negative constraints: %s
                Screen type: %s.
                """.formatted(
                pacingLine,
                stringValue(consistencyBible == null ? null : consistencyBible.get("negativePrompt")),
                normalizeScreenType(screenType)
        ).trim();
    }

    private Map<String, Object> shotVideoContinuity(
            GeneratedScriptResponse.CinematicShot shot,
            int shotNumber,
            List<GeneratedScriptResponse.CinematicShot> shots,
            int index,
            Map<String, Object> consistencyBible
    ) {
        Map<String, Object> continuity = new LinkedHashMap<>();
        continuity.put("shotNumber", shotNumber);
        continuity.put("previousShot", adjacentShotSummary(shots, index - 1));
        continuity.put("nextShot", adjacentShotSummary(shots, index + 1));
        continuity.put("lockedActors", listText(shot == null ? null : shot.getPrimaryActors()));
        continuity.put("lockedSideActors", listText(shot == null ? null : shot.getSideActors()));
        continuity.put("lockedSet", defaultString(shot == null ? null : shot.getSetDesign(), shot == null ? null : shot.getEnvironment()));
        continuity.put("lockedCamera", compactText(List.of(
                defaultString(shot == null ? null : shot.getShotType(), ""),
                defaultString(shot == null ? null : shot.getCameraAngle(), ""),
                defaultString(shot == null ? null : shot.getCameraMovement(), ""),
                defaultString(shot == null ? null : shot.getLensSuggestion(), "")
        )));
        continuity.put("lockedLighting", defaultString(shot == null ? null : shot.getLighting(), ""));
        continuity.put("globalNegativePrompt", stringValue(consistencyBible == null ? null : consistencyBible.get("negativePrompt")));
        return continuity;
    }

    private Map<String, Object> adjacentShotSummary(List<GeneratedScriptResponse.CinematicShot> shots, int index) {
        if (shots == null || index < 0 || index >= shots.size() || shots.get(index) == null) {
            return Map.of();
        }
        GeneratedScriptResponse.CinematicShot shot = shots.get(index);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("shotNumber", shot.getShotNumber());
        summary.put("title", shot.getTitle());
        summary.put("action", truncate(defaultString(shot.getAction(), ""), 180));
        summary.put("camera", compactText(List.of(defaultString(shot.getShotType(), ""), defaultString(shot.getCameraAngle(), ""), defaultString(shot.getCameraMovement(), ""))));
        summary.put("set", defaultString(shot.getSetDesign(), shot.getEnvironment()));
        return summary;
    }

    private String shotPacingPrompt(String pacingKey, GeneratedScriptResponse.CinematicShot shot) {
        String base = switch (pacingKey) {
            case "fast_paced" -> "Keep this shot kinetic: visible motion, sharp hook/cut timing, no dead air, captions short and bold.";
            case "slow_paced" -> "Let this shot breathe: steady framing, slower motion, expressive pause, captions readable and restrained.";
            default -> "Use balanced short-form pacing: direct action, readable caption, and a clean transition point.";
        };
        return base + " Duration target: " + Math.round(durationForShot(shot)) + " seconds.";
    }

    private String seedanceScenePrompt(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            GeneratedScriptResponse.CinematicShot shot,
            int shotNumber,
            String pacingKey,
            String globalPrompt
    ) {
        return """
                %s

                SCENE %02d PROMPT:
                Title: %s
                Action: %s
                Dialogue/VO: %s
                Text/captions: %s
                Camera: %s
                Set and props: %s
                Lighting: %s
                Pacing: %s
                Continuity: %s
                """.formatted(
                globalPrompt,
                shotNumber,
                defaultString(shot == null ? null : shot.getTitle(), "Scene"),
                defaultString(shot == null ? null : shot.getAction(), ""),
                defaultString(shot == null ? null : shot.getVoiceOver(), dialogueText(shot == null ? null : shot.getDialogue())),
                defaultString(shot == null ? null : shot.getTextOverlay(), captionTextForShot(shot)),
                compactText(List.of(
                        defaultString(shot == null ? null : shot.getShotType(), ""),
                        defaultString(shot == null ? null : shot.getCameraAngle(), ""),
                        defaultString(shot == null ? null : shot.getCameraMovement(), ""),
                        defaultString(shot == null ? null : shot.getLensSuggestion(), "")
                )),
                defaultString(shot == null ? null : shot.getSetDesign(), shot == null ? null : shot.getEnvironment()),
                defaultString(shot == null ? null : shot.getLighting(), ""),
                shotPacingPrompt(pacingKey, shot),
                stringValue(shot == null ? null : shot.getVideoContinuity())
        ).trim();
    }

    private List<Map<String, Object>> normalizedCaptionTrackForShot(
            GeneratedScriptResponse.CinematicShot shot,
            double shotStart,
            double shotEnd
    ) {
        List<Map<String, Object>> captions = mapListValue(shot == null ? null : shot.getCaptionTrack());
        if (captions.isEmpty()) {
            Map<String, Object> caption = new LinkedHashMap<>();
            caption.put("start", shotStart);
            caption.put("end", shotEnd);
            caption.put("text", captionTextForShot(shot));
            caption.put("style", "subtitle");
            return List.of(caption);
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> caption : captions) {
            if (caption == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(caption);
            double start = secondsValue(firstValue(item, "start", "startTime", "start_time"), shotStart);
            double end = secondsValue(firstValue(item, "end", "endTime", "end_time"), Math.min(shotEnd, start + 2.5d));
            if (start < shotStart && start >= 0d && start < durationForShot(shot) + 0.25d) {
                start = shotStart + start;
            }
            if (end <= durationForShot(shot) + 0.25d && end <= shotEnd - shotStart + 0.25d) {
                end = shotStart + end;
            }
            end = Math.max(start + 0.5d, Math.min(Math.max(shotEnd, start + 0.5d), end));
            item.put("start", start);
            item.put("end", end);
            item.put("text", truncate(defaultString(stringValue(firstValue(item, "text", "caption", "line")), captionTextForShot(shot)), 120));
            item.putIfAbsent("style", "subtitle");
            normalized.add(item);
        }
        return normalized.isEmpty() ? normalizedCaptionTrackForShot(null, shotStart, shotEnd) : normalized;
    }

    private List<Map<String, Object>> srtCuesForShot(int shotNumber, List<Map<String, Object>> captions, double shotStart, double shotEnd) {
        List<Map<String, Object>> cues = new ArrayList<>();
        for (Map<String, Object> caption : captions == null ? List.<Map<String, Object>>of() : captions) {
            if (caption == null) {
                continue;
            }
            String text = truncate(stringValue(caption.get("text")), 180);
            if (text.isBlank()) {
                continue;
            }
            double start = secondsValue(caption.get("start"), shotStart);
            double end = secondsValue(caption.get("end"), shotEnd);
            if (end <= start) {
                end = start + 1.2d;
            }
            Map<String, Object> cue = new LinkedHashMap<>();
            cue.put("shotNumber", shotNumber);
            cue.put("startSeconds", Math.max(0d, start));
            cue.put("endSeconds", Math.max(start + 0.5d, end));
            cue.put("text", text);
            cues.add(cue);
        }
        return cues;
    }

    private List<Map<String, Object>> normalizeSrtCueSequence(List<Map<String, Object>> cues) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        List<Map<String, Object>> sorted = new ArrayList<>(cues == null ? List.of() : cues);
        sorted.sort((left, right) -> Double.compare(
                secondsValue(left == null ? null : left.get("startSeconds"), 0d),
                secondsValue(right == null ? null : right.get("startSeconds"), 0d)
        ));
        int index = 1;
        double lastEnd = 0d;
        for (Map<String, Object> cue : sorted) {
            if (cue == null) {
                continue;
            }
            double start = Math.max(lastEnd, secondsValue(cue.get("startSeconds"), lastEnd));
            double end = Math.max(start + 0.5d, secondsValue(cue.get("endSeconds"), start + 1.5d));
            Map<String, Object> item = new LinkedHashMap<>(cue);
            item.put("index", index++);
            item.put("startSeconds", start);
            item.put("endSeconds", end);
            item.put("startTimecode", formatSrtTimestamp(start));
            item.put("endTimecode", formatSrtTimestamp(end));
            normalized.add(item);
            lastEnd = end;
        }
        return normalized;
    }

    private String buildSrt(List<Map<String, Object>> cues) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> cue : cues == null ? List.<Map<String, Object>>of() : cues) {
            int index = integerValue(cue.get("index"), builder.length() == 0 ? 1 : 0);
            builder.append(index <= 0 ? "" : index).append("\n");
            builder.append(defaultString(stringValue(cue.get("startTimecode")), formatSrtTimestamp(secondsValue(cue.get("startSeconds"), 0d))))
                    .append(" --> ")
                    .append(defaultString(stringValue(cue.get("endTimecode")), formatSrtTimestamp(secondsValue(cue.get("endSeconds"), 1d))))
                    .append("\n");
            builder.append(stringValue(cue.get("text")).replaceAll("[\\r\\n]+", " ").trim()).append("\n\n");
        }
        return builder.toString();
    }

    private String formatSrtTimestamp(double seconds) {
        double safe = Math.max(0d, seconds);
        long totalMillis = Math.round(safe * 1000d);
        long hours = totalMillis / 3_600_000L;
        long minutes = (totalMillis % 3_600_000L) / 60_000L;
        long secs = (totalMillis % 60_000L) / 1000L;
        long millis = totalMillis % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    private String captionTextForShot(GeneratedScriptResponse.CinematicShot shot) {
        if (shot == null) {
            return "";
        }
        String text = defaultString(shot.getTextOverlay(), "");
        if (text.isBlank()) {
            text = defaultString(shot.getVoiceOver(), "");
        }
        if (text.isBlank()) {
            text = dialogueText(shot.getDialogue());
        }
        if (text.isBlank()) {
            text = defaultString(shot.getTitle(), "");
        }
        return truncate(text, 120);
    }

    private double timelineStartFallback(List<GeneratedScriptResponse.CinematicShot> shots, int index) {
        double cursor = 0d;
        for (int i = 0; i < index && i < (shots == null ? 0 : shots.size()); i++) {
            cursor += durationForShot(shots.get(i));
        }
        return cursor;
    }

    private double durationForShot(GeneratedScriptResponse.CinematicShot shot) {
        if (shot == null) {
            return 3d;
        }
        if (shot.getDurationSeconds() != null && shot.getDurationSeconds() > 0d) {
            return shot.getDurationSeconds();
        }
        double start = secondsValue(shot.getStartTime(), 0d);
        double end = secondsValue(shot.getEndTime(), start + 3d);
        return Math.max(1d, end - start);
    }

    private Map<String, Object> mergeDefaults(Map<String, Object> existing, Map<String, Object> defaults) {
        Map<String, Object> merged = new LinkedHashMap<>(defaults == null ? Map.of() : defaults);
        if (existing != null) {
            merged.putAll(existing);
        }
        return merged;
    }

    private String compactText(List<String> parts) {
        List<String> cleaned = new ArrayList<>();
        for (String part : parts == null ? List.<String>of() : parts) {
            String text = defaultString(part, "").trim();
            if (!text.isBlank() && !cleaned.contains(text)) {
                cleaned.add(text);
            }
        }
        return String.join(", ", cleaned);
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
        scriptPayloadMap.put("storytellingType", defaultString(storyScript.getStorytellingType(), "narrator_visual_mix"));
        scriptPayloadMap.put("storytellingGuidance", storyScript.getStorytellingGuidance() == null ? Map.of() : storyScript.getStorytellingGuidance());
        scriptPayloadMap.put("hookLens", defaultString(storyScript.getHookLens(), "direct"));
        scriptPayloadMap.put("hookLensGuidance", storyScript.getHookLensGuidance() == null ? Map.of() : storyScript.getHookLensGuidance());
        scriptPayloadMap.put("hookBridge", storyScript.getHookBridge() == null ? Map.of() : storyScript.getHookBridge());
        scriptPayloadMap.put("factualityNotes", storyScript.getFactualityNotes() == null ? Map.of() : storyScript.getFactualityNotes());
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
        if (idea == null || idea.getProjectId() == null) {
            return;
        }
        projectService.markSelectedIdea(idea);
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
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        copyIfPresent(context, creativeNotes, "briefMode");
        copyIfPresent(context, creativeNotes, "marketingAgentMode");
        copyIfPresent(context, creativeNotes, "productInputKey");
        copyIfPresent(context, creativeNotes, "productIntelligenceBrief");
        copyIfPresent(context, creativeNotes, "productUnderstanding");
        copyIfPresent(context, creativeNotes, "adConceptLanes");
        copyIfPresent(context, creativeNotes, "brandContext");
        copyIfPresent(context, creativeNotes, "campaignObjective");
        String title = idea.getTitle();
        String summary = idea.getSummary();
        Map<String, Object> sourceBrief = mapValue(context.get("sourceBrief"));
        if (sourceBrief.isEmpty()) {
            sourceBrief = new LinkedHashMap<>(context);
        }
        if (isNoHumanProductAdBrief(sourceBrief)) {
            int ideaNumber = integerValue(context.get("generatedIndex"), 1);
            IdeaCandidate productOnly = enforceNoHumanProductIdeaCandidate(
                    idea,
                    sourceBrief,
                    ideaNumber,
                    new IdeaCandidate(title, summary, hashtags, creativeNotes)
            );
            title = productOnly.title();
            summary = productOnly.summary();
            creativeNotes = new LinkedHashMap<>(productOnly.creativeNotes());
        }
        return new GeneratedIdeaResponse(
                idea.getId(),
                UUID.fromString(String.valueOf(context.get("parentLockedIdeaId"))),
                idea.getProjectId(),
                title,
                summary,
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

    private String normalizeStorytellingType(String requestedStorytellingType) {
        String storytellingType = defaultString(requestedStorytellingType, "narrator_visual_mix")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (storytellingType) {
            case "talking_head", "talking_head_explainer" -> "talking_head_explainer";
            case "visual_voiceover", "visual_vo", "broll_voiceover" -> "visual_voiceover";
            case "dialogue_scene", "acted_dialogue", "character_dialogue" -> "dialogue_scene";
            case "dramatic_scene", "drama", "cinematic_drama" -> "dramatic_scene";
            default -> "narrator_visual_mix";
        };
    }

    private String resolveScreenplayStorytellingType(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext,
            GeneratedStoryScriptResponse.StoryScript storyScript
    ) {
        String requested = request == null ? null : request.storytellingType();
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(requestContext == null ? null : requestContext.get("storytellingType"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("storytellingType"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(creatorContext == null ? null : creatorContext.get("storytellingType"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = storyScript == null ? null : storyScript.getStorytellingType();
        }
        return normalizeStorytellingType(requested);
    }

    private String normalizeHookLens(String requestedHookLens) {
        String hookLens = defaultString(requestedHookLens, "direct")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (hookLens) {
            case "historical", "history_hook", "from_history" -> "history";
            case "geo", "place", "location", "geography_hook", "from_geography" -> "geography";
            case "philosophical", "philosophy_hook", "from_philosophy" -> "philosophy";
            case "scientific", "science_hook", "from_science" -> "science";
            case "cultural", "culture_hook", "from_culture" -> "culture";
            case "psychological", "psychology_hook", "from_psychology" -> "psychology";
            case "economic", "economics_hook", "from_economics", "business" -> "economics";
            case "history", "geography", "philosophy", "science", "culture", "psychology", "economics" -> hookLens;
            default -> "direct";
        };
    }

    private String resolveScreenplayHookLens(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext,
            GeneratedStoryScriptResponse.StoryScript storyScript
    ) {
        String requested = request == null ? null : request.hookLens();
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(requestContext == null ? null : requestContext.get("hookLens"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("hookLens"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = stringValue(creatorContext == null ? null : creatorContext.get("hookLens"));
        }
        if (defaultString(requested, "").isBlank()) {
            requested = storyScript == null ? null : storyScript.getHookLens();
        }
        return normalizeHookLens(requested);
    }

    private Map<String, Object> hookLensGuidanceFor(String hookLens) {
        String normalized = normalizeHookLens(hookLens);
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("hookLens", normalized);
        guidance.put("useExternalBridge", !"direct".equals(normalized));
        guidance.put("allowedLenses", List.of("direct", "history", "geography", "philosophy", "science", "culture", "psychology", "economics"));
        guidance.put("factualityRule", "Use only reliable, commonly known facts. Do not invent dates, places, people, events, causes, quotes, or links.");
        guidance.put("fallbackRule", "If no accurate bridge exists, start directly with the original story and set hookBridge.relationConfidence to none.");
        guidance.put("bridgeStyle", "direct".equals(normalized)
                ? "Start directly with the original story, conflict, or premise."
                : "Open with a factual " + normalized + " reference only when it truthfully relates to the original story. The bridge may be an analogy, context, or transition, but not a fabricated causal link.");
        guidance.put("mustAvoid", List.of(
                "false historical or scientific claims",
                "made-up dates, locations, people, events, or quotes",
                "forced analogies presented as fact",
                "causal links that are not supported by the story or common knowledge"
        ));
        return guidance;
    }

    private Map<String, Object> defaultHookBridgeFor(String hookLens) {
        String normalized = normalizeHookLens(hookLens);
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("hookLens", normalized);
        bridge.put("factualHook", "");
        bridge.put("bridgeLine", "");
        bridge.put("relationConfidence", "direct".equals(normalized) ? "not_applicable" : "none");
        bridge.put("noFalseLinkReason", "direct".equals(normalized)
                ? "Direct hook selected."
                : "No external factual bridge has been verified. Use a direct opening unless a reliable relation can be stated without inventing facts.");
        return bridge;
    }

    private Map<String, Object> defaultFactualityNotesFor(String hookLens) {
        String normalized = normalizeHookLens(hookLens);
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("hookLens", normalized);
        notes.put("verifiedFacts", List.of());
        notes.put("avoidedClaims", "direct".equals(normalized)
                ? List.of()
                : List.of("No unverified external facts or forced links were added by fallback generation."));
        notes.put("requiresHumanFactCheck", !"direct".equals(normalized));
        return notes;
    }

    private Map<String, Object> storytellingGuidanceFor(String storytellingType) {
        String normalized = normalizeStorytellingType(storytellingType);
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("storytellingType", normalized);
        guidance.put("recordOrGenerateVisuals", "narrator_visual_mix".equals(normalized) || "visual_voiceover".equals(normalized));
        switch (normalized) {
            case "talking_head_explainer" -> {
                guidance.put("primaryMode", "narrator_face");
                guidance.put("narratorFacePercent", 80);
                guidance.put("relatedVisualPercent", 20);
                guidance.put("dialogueStyle", "simple direct narration with light supporting dialogue only when natural");
            }
            case "visual_voiceover" -> {
                guidance.put("primaryMode", "related_visual");
                guidance.put("narratorFacePercent", 10);
                guidance.put("relatedVisualPercent", 90);
                guidance.put("dialogueStyle", "voice over carries the story; on-camera dialogue can be minimal or empty");
            }
            case "dialogue_scene" -> {
                guidance.put("primaryMode", "acted_dialogue");
                guidance.put("narratorFacePercent", 10);
                guidance.put("relatedVisualPercent", 20);
                guidance.put("dialogueStyle", "natural character dialogue with simple, engaging lines");
            }
            case "dramatic_scene" -> {
                guidance.put("primaryMode", "dramatic_scene");
                guidance.put("narratorFacePercent", 0);
                guidance.put("relatedVisualPercent", 20);
                guidance.put("dialogueStyle", "cinematic acted scene with emotional but concise dialogue");
            }
            default -> {
                guidance.put("primaryMode", "narrator_visual_mix");
                guidance.put("narratorFacePercent", 40);
                guidance.put("relatedVisualPercent", 60);
                guidance.put("dialogueStyle", "simple narration with engaging dialogue; alternate narrator face and related visuals");
            }
        }
        return guidance;
    }

    private Map<String, Object> shotMixPlanFor(String storytellingType) {
        Map<String, Object> guidance = storytellingGuidanceFor(storytellingType);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("narratorFacePercent", guidance.getOrDefault("narratorFacePercent", 40));
        plan.put("relatedVisualPercent", guidance.getOrDefault("relatedVisualPercent", 60));
        plan.put("recordOrGenerateVisualsNote", Boolean.TRUE.equals(guidance.get("recordOrGenerateVisuals"))
                ? "Related visual shots may be recorded by the user or generated from assetGenerationPrompt."
                : "");
        return plan;
    }

    private Map<String, Object> productionStyleContextFromSelection(Map<String, Object> selectionContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (selectionContext == null || selectionContext.isEmpty()) {
            return context;
        }
        copyProductionStyleValue(context, selectionContext, "productionStyle");
        copyProductionStyleValue(context, selectionContext, "hybridSceneMode");
        copyProductionStyleValue(context, selectionContext, "brollStyle");
        copyProductionStyleValue(context, selectionContext, "captionStyle");
        copyProductionStyleValue(context, selectionContext, "productionStyleGuidance");
        copyProductionStyleValue(context, selectionContext, "screenplayVideoGenerationPackage");

        Map<String, Object> packageContext = mapValue(context.get("screenplayVideoGenerationPackage"));
        if (!packageContext.isEmpty()) {
            copyProductionStyleValue(context, packageContext, "productionStyle");
            copyProductionStyleValue(context, packageContext, "hybridSceneMode");
            copyProductionStyleValue(context, packageContext, "brollStyle");
            copyProductionStyleValue(context, packageContext, "captionStyle");
            copyProductionStyleValue(context, packageContext, "productionStyleGuidance");
        }

        Map<String, Object> guidance = mapValue(context.get("productionStyleGuidance"));
        if (!hasContextValue(context.get("productionStyle"))) {
            Object guidanceMode = guidance.get("mode");
            if (hasContextValue(guidanceMode)) {
                context.put("productionStyle", guidanceMode);
            }
        }

        boolean hasProductionChoice = hasContextValue(context.get("productionStyle"))
                || hasContextValue(context.get("hybridSceneMode"))
                || hasContextValue(context.get("brollStyle"))
                || hasContextValue(context.get("captionStyle"))
                || !guidance.isEmpty();
        if (!hasProductionChoice) {
            return context;
        }

        String productionStyle = normalizeProductionStyle(stringValue(context.get("productionStyle")));
        String hybridSceneMode = normalizeHybridSceneMode(stringValue(context.get("hybridSceneMode")));
        String brollStyle = normalizeBrollStyle(stringValue(context.get("brollStyle")));
        String captionStyle = normalizeCaptionStyle(stringValue(context.get("captionStyle")));
        context.put("productionStyle", productionStyle);
        context.put("hybridSceneMode", hybridSceneMode);
        context.put("brollStyle", brollStyle);
        context.put("captionStyle", captionStyle);
        context.put("productionStyleGuidance", mergeDefaults(
                guidance,
                productionStyleGuidanceFor(productionStyle, hybridSceneMode, brollStyle, captionStyle)
        ));
        return context;
    }

    private void copyProductionStyleValue(Map<String, Object> target, Map<String, Object> source, String key) {
        if (hasContextValue(target.get(key))) {
            return;
        }
        Object value = firstProductionStyleValue(source, key);
        if (hasContextValue(value)) {
            target.put(key, value);
        }
    }

    private Object firstProductionStyleValue(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Object value = source.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> selectionPayload = mapValue(source.get("selectionPayload"));
        value = selectionPayload.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> ideaPayload = mapValue(selectionPayload.get("idea"));
        value = ideaPayload.get(key);
        if (hasContextValue(value)) {
            return value;
        }
        Map<String, Object> packagePayload = mapValue(selectionPayload.get("screenplayVideoGenerationPackage"));
        value = packagePayload.get(key);
        return hasContextValue(value) ? value : null;
    }

    private boolean hasContextValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private String normalizeProductionStyle(String value) {
        String normalized = defaultString(value, "hybrid")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "full_ai", "all_ai", "ai_only", "seedance_only" -> "full_ai";
            default -> "hybrid";
        };
    }

    private String normalizeHybridSceneMode(String value) {
        String normalized = defaultString(value, "ask_speaking_scenes")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "full_founder", "founder_only", "avatar_only", "talking_head_only", "all_founder" -> "full_founder";
            case "auto_mix", "human_first", "ai_first", "ask_speaking_scenes" -> normalized;
            default -> "ask_speaking_scenes";
        };
    }

    private String normalizeBrollStyle(String value) {
        String normalized = defaultString(value, "cinematic_social")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return normalized.isBlank() ? "cinematic_social" : normalized;
    }

    private String normalizeCaptionStyle(String value) {
        String normalized = defaultString(value, "bold_keyword")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return normalized.isBlank() ? "bold_keyword" : normalized;
    }

    private String resolveScreenplayProductionStyle(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext
    ) {
        return normalizeProductionStyle(firstNonBlank(
                request == null ? null : request.productionStyle(),
                stringValue(requestContext == null ? null : requestContext.get("productionStyle")),
                stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("productionStyle")),
                stringValue(creatorContext == null ? null : creatorContext.get("productionStyle"))
        ));
    }

    private String resolveScreenplayHybridSceneMode(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext
    ) {
        return normalizeHybridSceneMode(firstNonBlank(
                request == null ? null : request.hybridSceneMode(),
                stringValue(requestContext == null ? null : requestContext.get("hybridSceneMode")),
                stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("hybridSceneMode")),
                stringValue(creatorContext == null ? null : creatorContext.get("hybridSceneMode"))
        ));
    }

    private String resolveScreenplayBrollStyle(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext
    ) {
        return normalizeBrollStyle(firstNonBlank(
                request == null ? null : request.brollStyle(),
                stringValue(requestContext == null ? null : requestContext.get("brollStyle")),
                stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("brollStyle")),
                stringValue(creatorContext == null ? null : creatorContext.get("brollStyle"))
        ));
    }

    private String resolveScreenplayCaptionStyle(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext
    ) {
        return normalizeCaptionStyle(firstNonBlank(
                request == null ? null : request.captionStyle(),
                stringValue(requestContext == null ? null : requestContext.get("captionStyle")),
                stringValue(lockedPackageContext == null ? null : lockedPackageContext.get("captionStyle")),
                stringValue(creatorContext == null ? null : creatorContext.get("captionStyle"))
        ));
    }

    private Map<String, Object> resolveScreenplayProductionStyleGuidance(
            GenerateStoryIdeaScriptRequest request,
            Map<String, Object> requestContext,
            Map<String, Object> lockedPackageContext,
            Map<String, Object> creatorContext,
            String productionStyle,
            String hybridSceneMode,
            String brollStyle,
            String captionStyle
    ) {
        Map<String, Object> requestedGuidance = nonEmptyMap(
                request == null ? null : toGenericMap(request.productionStyleGuidance()),
                nonEmptyMap(
                        mapValue(requestContext == null ? null : requestContext.get("productionStyleGuidance")),
                        nonEmptyMap(
                                mapValue(lockedPackageContext == null ? null : lockedPackageContext.get("productionStyleGuidance")),
                                mapValue(creatorContext == null ? null : creatorContext.get("productionStyleGuidance"))
                        )
                )
        );
        return mergeDefaults(requestedGuidance, productionStyleGuidanceFor(productionStyle, hybridSceneMode, brollStyle, captionStyle));
    }

    private Map<String, Object> productionStyleGuidanceFor(String productionStyle, String hybridSceneMode, String brollStyle, String captionStyle) {
        String normalizedStyle = normalizeProductionStyle(productionStyle);
        String normalizedHybrid = normalizeHybridSceneMode(hybridSceneMode);
        String normalizedBroll = normalizeBrollStyle(brollStyle);
        String normalizedCaption = normalizeCaptionStyle(captionStyle);
        boolean fullFounder = "hybrid".equals(normalizedStyle) && "full_founder".equals(normalizedHybrid);
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("mode", normalizedStyle);
        guidance.put("label", "full_ai".equals(normalizedStyle) ? "Full AI" : "Hybrid");
        guidance.put("aiScenePercent", "full_ai".equals(normalizedStyle) ? 100 : fullFounder ? 0 : 65);
        guidance.put("talkingHeadPercent", "full_ai".equals(normalizedStyle) ? 0 : fullFounder ? 100 : 35);
        guidance.put("hybridSceneMode", normalizedHybrid);
        guidance.put("brollStyle", normalizedBroll);
        guidance.put("captionStyle", normalizedCaption);
        guidance.put("seedanceMaxClipSeconds", 15);
        guidance.put("scenePlanningRule", "full_ai".equals(normalizedStyle)
                ? "Every timeline scene must be generated as AI video with Seedance-compatible prompts; do not mark narrator or talking-head shots as recorded."
                : fullFounder
                        ? "Every timeline scene must show the uploaded founder with generationMode talking_head. Split all spoken dialogue into consecutive model-safe clips without dropping or paraphrasing words."
                        : "Mix human talking-head shots with generated AI visual scenes; mark each scene with generationMode talking_head or ai_generated.");
        guidance.put("brollRule", "Use " + normalizedBroll + " B-roll for visual support, transitions, and non-speaking inserts.");
        guidance.put("captionRule", "Use " + normalizedCaption + " captions and generate a complete SRT file.");
        guidance.put("mergeRule", "Generate each timeline scene as <=15 second clips, then merge clips in timeline order to meet the target duration.");
        return guidance;
    }

    private void applyProductionStyleDefaults(
            GeneratedScriptResponse.CinematicScript scriptPayload,
            String productionStyle,
            String hybridSceneMode,
            String brollStyle,
            String captionStyle,
            Map<String, Object> productionStyleGuidance
    ) {
        if (scriptPayload == null) {
            return;
        }
        String normalizedStyle = normalizeProductionStyle(productionStyle);
        String normalizedHybrid = normalizeHybridSceneMode(hybridSceneMode);
        String normalizedBroll = normalizeBrollStyle(brollStyle);
        String normalizedCaption = normalizeCaptionStyle(captionStyle);
        scriptPayload.setProductionStyle(normalizedStyle);
        scriptPayload.setHybridSceneMode(normalizedHybrid);
        scriptPayload.setBrollStyle(normalizedBroll);
        scriptPayload.setCaptionStyle(normalizedCaption);
        scriptPayload.setProductionStyleGuidance(mergeDefaults(scriptPayload.getProductionStyleGuidance(), productionStyleGuidanceFor(normalizedStyle, normalizedHybrid, normalizedBroll, normalizedCaption)));
        if (productionStyleGuidance != null && !productionStyleGuidance.isEmpty()) {
            scriptPayload.setProductionStyleGuidance(mergeDefaults(productionStyleGuidance, scriptPayload.getProductionStyleGuidance()));
        }
        List<GeneratedScriptResponse.CinematicShot> shots = scriptPayload.getShots();
        if (shots == null || shots.isEmpty()) {
            return;
        }
        for (GeneratedScriptResponse.CinematicShot shot : shots) {
            if (shot == null) {
                continue;
            }
            shot.setBrollStyle(defaultString(shot.getBrollStyle(), normalizedBroll));
            shot.setCaptionStyle(defaultString(shot.getCaptionStyle(), normalizedCaption));
            if ("full_ai".equals(normalizedStyle)) {
                shot.setGenerationMode("ai_generated");
                shot.setTargetProvider("seedance");
                shot.setAssetCaptureMode("generate");
                shot.setAssetGenerationPrompt(defaultString(
                        shot.getAssetGenerationPrompt(),
                        "Generate this full-AI scene from the screenplay action, dialogue, captions, and Seedance prompt."
                ));
            } else if ("full_founder".equals(normalizedHybrid)) {
                shot.setGenerationMode("talking_head");
                shot.setAssetCaptureMode("record");
            } else {
                String existingMode = defaultString(shot.getGenerationMode(), "");
                if (existingMode.isBlank()) {
                    String assetMode = defaultString(shot.getAssetCaptureMode(), "").toLowerCase(Locale.ROOT);
                    shot.setGenerationMode(assetMode.contains("generate") ? "ai_generated" : "talking_head");
                }
                if (defaultString(shot.getTargetProvider(), "").isBlank() && "ai_generated".equals(defaultString(shot.getGenerationMode(), ""))) {
                    shot.setTargetProvider("seedance");
                }
            }
        }
    }

    private String storytellingRoleFor(int shotNumber, String storytellingType) {
        String normalized = normalizeStorytellingType(storytellingType);
        return switch (normalized) {
            case "talking_head_explainer" -> shotNumber % 5 == 3 ? "related_visual" : "narrator_face";
            case "visual_voiceover" -> shotNumber == 1 ? "narrator_face" : "related_visual";
            case "dialogue_scene", "dramatic_scene" -> "acted_dialogue";
            default -> shotNumber % 3 == 1 ? "narrator_face" : "related_visual";
        };
    }

    private String assetCaptureModeFor(String storytellingRole) {
        return "related_visual".equals(defaultString(storytellingRole, "")) ? "record_or_generate" : "record";
    }

    private String assetGenerationPromptFor(String storytellingRole, String title, String phase, String action, String screenType) {
        if (!"related_visual".equals(defaultString(storytellingRole, ""))) {
            return "";
        }
        return "Create a clean %s related visual or B-roll image for \"%s\" during the %s beat: %s"
                .formatted(normalizeScreenType(screenType), defaultString(title, "creator story"), defaultString(phase, "story"), defaultString(action, "show the idea clearly"));
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
        return "Professional storyboard sketch panel, hand-drawn animatic linework, loose pencil construction marks, clean ink outlines, selective muted marker color accents, " + shotType + ", " + cameraAngle
                + ", creator with " + expression
                + ", " + environmentFor(categoryCode)
                + ", natural soft lighting notes, action: " + action
                + ", drawn planning-frame style, visible wardrobe and set cues, not a black-and-white photo or glossy cinematic still, " + composition;
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

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? fallback : Boolean.parseBoolean(normalized);
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
