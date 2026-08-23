package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.IdeaOptionSource;
import com.dalai.llama.creativeplanning.domain.entity.IdeaOption;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.IdeaOptionView;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionRequest;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionResponse;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.repository.IdeaOptionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.generation.JsonExtraction;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The standalone-brief equivalent of what a campaign-planning chat does for Entry A: turn a
 * funded {@link ProjectRequirement} into idea options, then a single {@link LockedIdea} pre-
 * production-service can build a project from. Entry A extracts one idea from a conversation
 * (see {@link LockedIdeaService}); this generates several candidates up front and lets the
 * creator pick, since there's no conversation here to extract from.
 * <p>
 * Every option is persisted ({@link IdeaOption}), not just returned in the HTTP response --
 * a page refresh needs to see the same list it generated, not lose it to component state. Saving
 * an edited variant of an option writes a new row (source=EDITED, parentId=the option it came
 * from) rather than mutating the original, so the lineage from a generated option to whatever a
 * creator actually locked is always reconstructable.
 */
@Service
public class ProjectRequirementIdeaService {

    private static final String GENERATE_TASK_KEY = "PROJECT_REQUIREMENT_IDEA_GENERATION";
    private static final int DEFAULT_OPTION_COUNT = 3;

    private final ProjectRequirementService projectRequirementService;
    private final IdeaOptionRepository ideaOptionRepository;
    private final LockedIdeaRepository lockedIdeaRepository;
    private final PreProductionServiceClient preProductionServiceClient;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ProjectRequirementIdeaService(
            ProjectRequirementService projectRequirementService,
            IdeaOptionRepository ideaOptionRepository,
            LockedIdeaRepository lockedIdeaRepository,
            PreProductionServiceClient preProductionServiceClient,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRequirementService = projectRequirementService;
        this.ideaOptionRepository = ideaOptionRepository;
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.preProductionServiceClient = preProductionServiceClient;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** Only a funded requirement can generate ideas -- see the class javadoc: this is what the
     * "generate ideas" CTA on a project page calls once funded flips true. Every candidate the
     * model returns is saved immediately (source=GENERATED), not just handed back in the
     * response -- see {@link #listOptions} for how a refreshed page gets them back. */
    @Transactional
    public List<IdeaOptionView> generateOptions(UUID tenantId, UUID requirementId, Integer count) {
        ProjectRequirement requirement = projectRequirementService.requireRequirement(tenantId, requirementId);
        if (!requirement.isFunded()) {
            throw CreativePlanningException.badRequest("Requirement " + requirementId + " is not funded yet");
        }

        int optionCount = (count == null || count < 1) ? DEFAULT_OPTION_COUNT : Math.min(count, 5);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "requirement-ideas-" + requirementId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, GENERATE_TASK_KEY,
                        java.util.Map.of(
                                "briefText", requirement.getBriefText(),
                                "targetAudience", orNotSpecified(requirement.getTargetAudience()),
                                "campaignDirection", orNotSpecified(requirement.getCampaignDirection()),
                                "budgetTier", requirement.getBudgetTier().name(),
                                "optionCount", String.valueOf(optionCount)
                        )));

        OffsetDateTime now = OffsetDateTime.now();
        List<IdeaOption> saved = parseCandidates(response).stream()
                .map(candidate -> ideaOptionRepository.save(IdeaOption.builder()
                        .tenantId(tenantId)
                        .projectRequirementId(requirementId)
                        .title(candidate.title())
                        .concept(candidate.concept())
                        .targetAudience(candidate.targetAudience())
                        .campaignAngle(candidate.campaignAngle())
                        .keyMessage(candidate.keyMessage())
                        .tone(candidate.tone())
                        .source(IdeaOptionSource.GENERATED)
                        .parentId(null)
                        .createdAt(now)
                        .build()))
                .collect(Collectors.toList());

        return saved.stream().map(this::toOptionView).collect(Collectors.toList());
    }

    /** What a refreshed project-requirement page reads instead of losing the generated list --
     * every option ever saved for this requirement (both GENERATED batches and any EDITED
     * variants), newest first. */
    @Transactional(readOnly = true)
    public List<IdeaOptionView> listOptions(UUID tenantId, UUID requirementId) {
        projectRequirementService.requireRequirement(tenantId, requirementId);
        return ideaOptionRepository.findByProjectRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                .filter(option -> option.getTenantId().equals(tenantId))
                .map(this::toOptionView)
                .collect(Collectors.toList());
    }

    /** Saves a creator's edited version of an existing option as a NEW row (source=EDITED,
     * parentId=optionId) rather than overwriting it -- the original generated option stays
     * exactly as the model produced it. */
    @Transactional
    public IdeaOptionView saveEditedOption(UUID tenantId, UUID requirementId, UUID parentOptionId, LockIdeaOptionRequest edited) {
        projectRequirementService.requireRequirement(tenantId, requirementId);
        IdeaOption parent = ideaOptionRepository.findByIdAndTenantId(parentOptionId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No idea option " + parentOptionId));
        if (!parent.getProjectRequirementId().equals(requirementId)) {
            throw CreativePlanningException.badRequest("Idea option " + parentOptionId + " does not belong to requirement " + requirementId);
        }

        IdeaOption saved = ideaOptionRepository.save(IdeaOption.builder()
                .tenantId(tenantId)
                .projectRequirementId(requirementId)
                .title(edited.title())
                .concept(edited.concept())
                .targetAudience(edited.targetAudience())
                .campaignAngle(edited.campaignAngle())
                .keyMessage(edited.keyMessage())
                .tone(edited.tone())
                .source(IdeaOptionSource.EDITED)
                .parentId(parentOptionId)
                .createdAt(OffsetDateTime.now())
                .build());

        return toOptionView(saved);
    }

    /** Creates the real LockedIdea and hands off to pre-production-service in one transaction-ish
     * step (the pre-production call happens after the local commit succeeds conceptually, but
     * since there's no distributed transaction here, a pre-production failure rolls the local
     * LockedIdea insert back too -- see PreProductionServiceClient, which throws rather than
     * swallowing). Idempotent: locking twice for the same requirement returns the existing idea
     * rather than creating a second one or re-calling pre-production-service. */
    @Transactional
    public LockIdeaOptionResponse lockOption(UUID tenantId, UUID requirementId, LockIdeaOptionRequest chosen) {
        ProjectRequirement requirement = projectRequirementService.requireRequirement(tenantId, requirementId);
        if (!requirement.isFunded()) {
            throw CreativePlanningException.badRequest("Requirement " + requirementId + " is not funded yet");
        }

        var existing = lockedIdeaRepository.findByProjectRequirementId(requirementId);
        if (existing.isPresent()) {
            return new LockIdeaOptionResponse(toView(existing.get()), existing.get().getProjectId());
        }

        LockedIdea lockedIdea = lockedIdeaRepository.save(LockedIdea.builder()
                .tenantId(tenantId)
                .projectRequirementId(requirementId)
                .title(chosen.title())
                .concept(chosen.concept())
                .targetAudience(chosen.targetAudience())
                .campaignAngle(chosen.campaignAngle())
                .keyMessage(chosen.keyMessage())
                .tone(chosen.tone())
                .budgetTier(requirement.getBudgetTier())
                .createdAt(OffsetDateTime.now())
                .build());

        UUID projectId = preProductionServiceClient.createProjectFromLockedIdea(
                tenantId, lockedIdea.getId(), chosen.title(), requirement.getBudgetTier());
        lockedIdea.setProjectId(projectId);
        lockedIdeaRepository.save(lockedIdea);

        return new LockIdeaOptionResponse(toView(lockedIdea), projectId);
    }

    private static String orNotSpecified(String value) {
        return (value == null || value.isBlank()) ? "Not specified" : value;
    }

    /** Raw shape the LLM actually returns -- deliberately separate from {@link IdeaOptionView}
     * (which also carries id/source/parentId/createdAt, none of which the model produces) so
     * Jackson never has to guess which fields are real output versus persistence metadata. */
    private record RawIdeaCandidate(
            String title,
            String concept,
            String targetAudience,
            String campaignAngle,
            String keyMessage,
            String tone
    ) {
    }

    private List<RawIdeaCandidate> parseCandidates(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + GENERATE_TASK_KEY);
        }
        try {
            return objectMapper.readValue(
                    JsonExtraction.stripCodeFence(response.response()),
                    new TypeReference<List<RawIdeaCandidate>>() {
                    });
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not parse " + GENERATE_TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private IdeaOptionView toOptionView(IdeaOption option) {
        return new IdeaOptionView(option.getId(), option.getTitle(), option.getConcept(), option.getTargetAudience(),
                option.getCampaignAngle(), option.getKeyMessage(), option.getTone(), option.getSource(),
                option.getParentId(), option.getCreatedAt());
    }

    private LockedIdeaView toView(LockedIdea idea) {
        return new LockedIdeaView(idea.getId(), idea.getSessionId(), idea.getTitle(), idea.getConcept(),
                idea.getTargetAudience(), idea.getCampaignAngle(), idea.getKeyMessage(), idea.getTone(),
                idea.getBudgetTier(), idea.getCreatedAt());
    }
}
