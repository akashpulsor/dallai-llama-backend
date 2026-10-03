package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.ReviewActor;
import com.dalai.llama.preprod.domain.entity.CreativeDirection;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionFeedback;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionReference;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.dto.CreativeDirectionBoardView;
import com.dalai.llama.preprod.dto.CreativeDirectionFeedbackRequest;
import com.dalai.llama.preprod.dto.CreativeDirectionView;
import com.dalai.llama.preprod.repository.CreativeDirectionFeedbackRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionGenerationRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionReferenceRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.ProjectConfigService;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient.CreativeContext;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient.CreativeContext.ReferenceAsset;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creative Direction, the stage between the locked idea and hook/beat planning: three alternative
 * director's treatments, reviewed, revised and approved by the creator or the client.
 * <p>
 * Inputs (idea, brief, client references and their analysis) come from creative-planning-service,
 * which owns them; the treatments are written by PRE_PROD_CREATIVE_DIRECTION_GENERATE through the
 * existing llm-gateway path. No image or video is generated here.
 * <p>
 * Invariants: the AI recommendation is advisory (every treatment starts PROPOSED); at most one
 * treatment per project is APPROVED and it is never edited -- a revision is a new version, and a
 * new generation round supersedes only treatments nobody approved.
 */
@Slf4j
@Service
public class CreativeDirectionService {

    static final String GENERATE_TASK_KEY = "PRE_PROD_CREATIVE_DIRECTION_GENERATE";
    static final String REVISE_TASK_KEY = "PRE_PROD_CREATIVE_DIRECTION_REVISE";

    private static final List<CreativeDirectionReviewStatus> OPEN = List.of(
            CreativeDirectionReviewStatus.PROPOSED,
            CreativeDirectionReviewStatus.SELECTED,
            CreativeDirectionReviewStatus.REVISION_REQUESTED);

    private final ProjectRepository projectRepository;
    private final CreativeDirectionGenerationRepository generationRepository;
    private final CreativeDirectionRepository directionRepository;
    private final CreativeDirectionReferenceRepository referenceRepository;
    private final CreativeDirectionFeedbackRepository feedbackRepository;
    private final CreativePlanningClient creativePlanningClient;
    private final LlmGatewayClient llmGatewayClient;
    private final ProjectConfigService projectConfigService;
    private final CreativeDirectionMapper mapper;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public CreativeDirectionService(
            ProjectRepository projectRepository,
            CreativeDirectionGenerationRepository generationRepository,
            CreativeDirectionRepository directionRepository,
            CreativeDirectionReferenceRepository referenceRepository,
            CreativeDirectionFeedbackRepository feedbackRepository,
            CreativePlanningClient creativePlanningClient,
            LlmGatewayClient llmGatewayClient,
            ProjectConfigService projectConfigService,
            CreativeDirectionMapper mapper,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.generationRepository = generationRepository;
        this.directionRepository = directionRepository;
        this.referenceRepository = referenceRepository;
        this.feedbackRepository = feedbackRepository;
        this.creativePlanningClient = creativePlanningClient;
        this.llmGatewayClient = llmGatewayClient;
        this.projectConfigService = projectConfigService;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** Writes three new alternatives for the locked idea. Open treatments from earlier rounds are
     * superseded; an approved one is untouched and stays the project's contract until a newer
     * treatment is approved. */
    @Transactional
    public CreativeDirectionBoardView generate(UUID tenantId, UUID projectId, UUID userId) {
        Project project = requireProject(tenantId, projectId);
        CreativeContext context = creativePlanningClient.getCreativeContext(tenantId, projectId);
        int round = generationRepository.findTopByProjectIdOrderByRoundDesc(projectId)
                .map(previous -> previous.getRound() + 1).orElse(1);
        Integer durationSeconds = durationFor(projectId, context);

        Map<String, String> variables = new LinkedHashMap<>(inputVariables(context, durationSeconds));
        CreativeDirectionGenerationResult result = callModel(tenantId, projectId,
                "creative-direction-" + projectId + "-round" + round, GENERATE_TASK_KEY, variables,
                CreativeDirectionGenerationResult.class).validated();

        OffsetDateTime now = OffsetDateTime.now();
        directionRepository.findByProjectIdAndReviewStatusIn(projectId, OPEN)
                .forEach(open -> supersede(open, now));
        CreativeDirectionGeneration generation = generationRepository.save(snapshot(tenantId, projectId, round, context,
                durationSeconds, userId, now));
        Map<UUID, ReferenceAsset> available = assetsById(context);
        List<CreativeDirectionGenerationResult.Direction> directions = result.directions();
        for (int i = 0; i < directions.size(); i++) {
            boolean recommended = i == 0;
            CreativeDirection saved = directionRepository.save(newDirection(tenantId, projectId, generation.getId(),
                    i + 1, 1, null, directions.get(i), recommended, recommended ? result.recommendationReason() : null,
                    CreativeDirectionReviewStatus.PROPOSED, now));
            saveReferences(saved.getId(), directions.get(i).referenceAssetIds(), available, now);
        }
        return board(project, available);
    }

    /** The Creative Direction screen. Reference URLs are signed fresh by creative-planning; when it
     * cannot be reached the treatments still render, with references marked unavailable. */
    @Transactional(readOnly = true)
    public CreativeDirectionBoardView board(UUID tenantId, UUID projectId) {
        Project project = requireProject(tenantId, projectId);
        return board(project, liveAssets(tenantId, projectId));
    }

    @Transactional(readOnly = true)
    public CreativeDirectionView get(UUID tenantId, UUID projectId, UUID directionId) {
        requireProject(tenantId, projectId);
        return toView(requireDirection(tenantId, projectId, directionId), liveAssets(tenantId, projectId));
    }

    @Transactional(readOnly = true)
    public Optional<CreativeDirectionView> approved(UUID tenantId, UUID projectId) {
        requireProject(tenantId, projectId);
        return directionRepository.findByProjectIdAndReviewStatus(projectId, CreativeDirectionReviewStatus.APPROVED)
                .map(direction -> toView(direction, liveAssets(tenantId, projectId)));
    }

    /** Marks the treatment the reviewer is working on; at most one is SELECTED per project. An
     * approved treatment is already the project's choice and is left as it is. */
    @Transactional
    public CreativeDirectionView select(UUID tenantId, UUID projectId, UUID directionId) {
        CreativeDirection direction = requireOpenOrApproved(tenantId, projectId, directionId);
        if (direction.getReviewStatus() != CreativeDirectionReviewStatus.APPROVED) {
            OffsetDateTime now = OffsetDateTime.now();
            deselectOthers(projectId, directionId, now);
            direction.setReviewStatus(CreativeDirectionReviewStatus.SELECTED);
            direction.setUpdatedAt(now);
            directionRepository.save(direction);
        }
        return toView(direction, liveAssets(tenantId, projectId));
    }

    /** Records a review note. {@code requestRevision} flags the treatment for revision -- except an
     * approved one, which is never mutated: its revision becomes a new version to approve. */
    @Transactional
    public CreativeDirectionView addFeedback(UUID tenantId, UUID projectId, UUID directionId,
                                             CreativeDirectionFeedbackRequest request, ReviewActor source, UUID authorUserId) {
        CreativeDirection direction = requireOpenOrApproved(tenantId, projectId, directionId);
        OffsetDateTime now = OffsetDateTime.now();
        feedbackRepository.save(CreativeDirectionFeedback.builder()
                .creativeDirectionId(directionId)
                .source(source)
                .authorUserId(authorUserId)
                .feedback(request.feedback().strip())
                .createdAt(now)
                .build());
        if (request.requestRevision() && direction.getReviewStatus() != CreativeDirectionReviewStatus.APPROVED) {
            direction.setReviewStatus(CreativeDirectionReviewStatus.REVISION_REQUESTED);
            direction.setUpdatedAt(now);
            directionRepository.save(direction);
        }
        return toView(direction, liveAssets(tenantId, projectId));
    }

    /** Rewrites one treatment against the feedback recorded on it (plus {@code note}) as a new
     * version. The revised version becomes the one under review; the old one is superseded unless
     * it is approved, in which case it stays approved until the new version is. */
    @Transactional
    public CreativeDirectionView revise(UUID tenantId, UUID projectId, UUID directionId, String note) {
        CreativeDirection current = requireOpenOrApproved(tenantId, projectId, directionId);
        List<CreativeDirectionFeedback> feedback = feedbackRepository.findByCreativeDirectionIdOrderByCreatedAtAsc(directionId);
        if (feedback.isEmpty() && (note == null || note.isBlank())) {
            throw PreProductionException.badRequest("Add feedback or a note describing what to change before requesting a revision");
        }
        CreativeContext context = creativePlanningClient.getCreativeContext(tenantId, projectId);
        Map<String, String> variables = new LinkedHashMap<>(inputVariables(context, durationFor(projectId, context)));
        variables.put("currentDirection", describe(current, referenceRepository.findByCreativeDirectionId(directionId)));
        variables.put("feedback", feedbackText(feedback, note));
        CreativeDirectionGenerationResult.Direction revised = callModel(tenantId, projectId,
                "creative-direction-revise-" + directionId + "-v" + (current.getVersion() + 1), REVISE_TASK_KEY, variables,
                CreativeDirectionGenerationResult.Direction.class);
        revised.validate("Revised creative direction");

        OffsetDateTime now = OffsetDateTime.now();
        if (current.getReviewStatus() != CreativeDirectionReviewStatus.APPROVED) {
            supersede(current, now);
        }
        deselectOthers(projectId, null, now);
        CreativeDirection next = directionRepository.save(newDirection(tenantId, projectId, current.getGenerationId(),
                current.getOptionNumber(), current.getVersion() + 1, current.getId(), revised, current.isRecommended(),
                current.getRecommendationReason(), CreativeDirectionReviewStatus.SELECTED, now));
        Map<UUID, ReferenceAsset> available = assetsById(context);
        saveReferences(next.getId(), revised.referenceAssetIds(), available, now);
        return toView(next, available);
    }

    /** Makes this treatment the project's creative contract. Any treatment may be approved, not only
     * the AI's recommendation; a previously approved one is superseded, never deleted. Content
     * already generated from the earlier direction is left in place -- it records which direction
     * it was built from, and the creator regenerates it explicitly. */
    @Transactional
    public CreativeDirectionView approve(UUID tenantId, UUID projectId, UUID directionId, ReviewActor via, UUID approvedBy) {
        CreativeDirection direction = requireOpenOrApproved(tenantId, projectId, directionId);
        if (direction.getReviewStatus() == CreativeDirectionReviewStatus.APPROVED) {
            return toView(direction, liveAssets(tenantId, projectId));
        }
        OffsetDateTime now = OffsetDateTime.now();
        directionRepository.findByProjectIdAndReviewStatus(projectId, CreativeDirectionReviewStatus.APPROVED)
                .ifPresent(previous -> {
                    supersede(previous, now);
                    directionRepository.flush();
                });
        deselectOthers(projectId, directionId, now);
        direction.setReviewStatus(CreativeDirectionReviewStatus.APPROVED);
        direction.setApprovedAt(now);
        direction.setApprovedBy(approvedBy);
        direction.setApprovedVia(via);
        direction.setUpdatedAt(now);
        return toView(directionRepository.save(direction), liveAssets(tenantId, projectId));
    }

    // ---------------------------------------------------------------- board

    private CreativeDirectionBoardView board(Project project, Map<UUID, ReferenceAsset> liveAssets) {
        Optional<CreativeDirectionGeneration> latest = generationRepository.findTopByProjectIdOrderByRoundDesc(project.getId());
        List<CreativeDirection> directions = latest
                .map(generation -> latestVersionPerOption(directionRepository.findByGenerationIdOrderByOptionNumberAscVersionDesc(generation.getId())))
                .orElse(List.of());
        Optional<CreativeDirection> approved = directionRepository.findByProjectIdAndReviewStatus(project.getId(),
                CreativeDirectionReviewStatus.APPROVED);

        List<UUID> ids = new ArrayList<>(directions.stream().map(CreativeDirection::getId).toList());
        approved.map(CreativeDirection::getId).filter(id -> !ids.contains(id)).ifPresent(ids::add);
        Map<UUID, List<CreativeDirectionReference>> references = groupBy(
                ids.isEmpty() ? List.of() : referenceRepository.findByCreativeDirectionIdIn(ids),
                CreativeDirectionReference::getCreativeDirectionId);
        Map<UUID, List<CreativeDirectionFeedback>> feedback = groupBy(
                ids.isEmpty() ? List.of() : feedbackRepository.findByCreativeDirectionIdInOrderByCreatedAtAsc(ids),
                CreativeDirectionFeedback::getCreativeDirectionId);
        Function<CreativeDirection, CreativeDirectionView> view = direction -> mapper.toView(direction,
                references.getOrDefault(direction.getId(), List.of()), feedback.getOrDefault(direction.getId(), List.of()), liveAssets);

        CreativeDirectionGeneration generation = latest.orElse(null);
        return new CreativeDirectionBoardView(
                project.getId(),
                project.isCreativeDirectionRequired(),
                generation == null ? 0 : generation.getRound(),
                mapper.toBoardIdea(generation),
                generation == null ? null : generation.getBriefText(),
                generation == null ? null : generation.getDurationSeconds(),
                directions.stream().map(view).toList(),
                approved.map(view).orElse(null));
    }

    /** One entry per option, its newest version -- the recommended option (1) first. */
    private static List<CreativeDirection> latestVersionPerOption(List<CreativeDirection> newestFirstPerOption) {
        Map<Integer, CreativeDirection> byOption = new LinkedHashMap<>();
        newestFirstPerOption.forEach(direction -> byOption.putIfAbsent(direction.getOptionNumber(), direction));
        return List.copyOf(byOption.values());
    }

    private CreativeDirectionView toView(CreativeDirection direction, Map<UUID, ReferenceAsset> liveAssets) {
        return mapper.toView(direction, referenceRepository.findByCreativeDirectionId(direction.getId()),
                feedbackRepository.findByCreativeDirectionIdOrderByCreatedAtAsc(direction.getId()), liveAssets);
    }

    // ---------------------------------------------------------------- state

    private void supersede(CreativeDirection direction, OffsetDateTime now) {
        direction.setReviewStatus(CreativeDirectionReviewStatus.SUPERSEDED);
        direction.setUpdatedAt(now);
        directionRepository.save(direction);
    }

    private void deselectOthers(UUID projectId, UUID keepId, OffsetDateTime now) {
        directionRepository.findByProjectIdAndReviewStatusIn(projectId, List.of(CreativeDirectionReviewStatus.SELECTED)).stream()
                .filter(selected -> !selected.getId().equals(keepId))
                .forEach(selected -> {
                    selected.setReviewStatus(CreativeDirectionReviewStatus.PROPOSED);
                    selected.setUpdatedAt(now);
                    directionRepository.save(selected);
                });
    }

    private Project requireProject(UUID tenantId, UUID projectId) {
        return projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
    }

    private CreativeDirection requireDirection(UUID tenantId, UUID projectId, UUID directionId) {
        return directionRepository.findByIdAndProjectIdAndTenantId(directionId, projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No creative direction " + directionId + " on project " + projectId));
    }

    private CreativeDirection requireOpenOrApproved(UUID tenantId, UUID projectId, UUID directionId) {
        CreativeDirection direction = requireDirection(tenantId, projectId, directionId);
        if (direction.getReviewStatus() == CreativeDirectionReviewStatus.SUPERSEDED) {
            throw PreProductionException.conflict("Creative direction " + directionId + " has been superseded by a newer version");
        }
        return direction;
    }

    // ---------------------------------------------------------------- model input/output

    private <T> T callModel(UUID tenantId, UUID projectId, String idempotencyKey, String taskKey,
                            Map<String, String> variables, Class<T> type) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(tenantId.toString(), idempotencyKey,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, taskKey, variables).withProjectId(projectId));
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream(taskKey + " returned no content");
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), type);
        } catch (JsonProcessingException ex) {
            throw PreProductionException.upstream(taskKey + " returned a response that is not the expected JSON", ex);
        }
    }

    private Integer durationFor(UUID projectId, CreativeContext context) {
        if (context.brief() != null && context.brief().durationSeconds() != null) {
            return context.brief().durationSeconds();
        }
        var config = projectConfigService.getEntityOrDefault(projectId);
        return config == null ? null : config.getTargetDurationSeconds();
    }

    private static Map<String, String> inputVariables(CreativeContext context, Integer durationSeconds) {
        return Map.of(
                "idea", ideaText(context.idea()),
                "brief", briefText(context.brief()),
                "durationSeconds", durationSeconds == null ? "not specified" : durationSeconds + " seconds",
                "references", referencesText(context.safeReferences()),
                "referenceAnalysis", referenceAnalysisText(context.safeReferences()));
    }

    private static String ideaText(CreativeContext.Idea idea) {
        if (idea == null) {
            return "Not available.";
        }
        return lines(labelled("Title", idea.title()), labelled("Concept", idea.concept()),
                labelled("Target audience", idea.targetAudience()), labelled("Campaign angle", idea.campaignAngle()),
                labelled("Key message", idea.keyMessage()), labelled("Tone", idea.tone()));
    }

    private static String briefText(CreativeContext.Brief brief) {
        if (brief == null) {
            return "No written brief -- this idea came from a campaign-planning conversation.";
        }
        return lines(brief.briefText(), labelled("Target audience", brief.targetAudience()),
                labelled("Campaign direction", brief.campaignDirection()));
    }

    private static String referencesText(List<ReferenceAsset> references) {
        if (references.isEmpty()) {
            return "None supplied.";
        }
        return references.stream()
                .map(reference -> "- assetId: " + reference.assetId() + " | mediaType: " + reference.mediaType()
                        + (reference.originalFilename() == null ? "" : " | file: " + reference.originalFilename())
                        + (isBlank(reference.clientInstruction()) ? "" : " | client instruction: " + reference.clientInstruction().strip()))
                .collect(Collectors.joining("\n"));
    }

    private static String referenceAnalysisText(List<ReferenceAsset> references) {
        String analysed = references.stream()
                .filter(reference -> !isBlank(reference.analysis()))
                .map(reference -> "- " + reference.assetId() + ": " + reference.analysis().strip())
                .collect(Collectors.joining("\n"));
        return analysed.isEmpty() ? "No analysis available." : analysed;
    }

    private static String describe(CreativeDirection direction, List<CreativeDirectionReference> references) {
        return lines(labelled("Title", direction.getTitle()), labelled("Creative concept", direction.getCreativeConcept()),
                labelled("Director's treatment", direction.getDirectorsTreatment()),
                labelled("Storytelling style", direction.getStorytellingStyle()),
                labelled("Story period", direction.getStoryPeriod()), labelled("Colour treatment", direction.getColorTreatment()),
                labelled("Contrast", direction.getContrast()), labelled("Texture", direction.getTexture()),
                labelled("Overall aesthetic", direction.getOverallAesthetic()),
                labelled("Cinematography philosophy", direction.getCinematographyPhilosophy()),
                labelled("Emotional journey", direction.getEmotionalJourney()),
                labelled("Sound direction", direction.getSoundDirection()),
                labelled("Signature creative device", direction.getSignatureCreativeDevice()),
                labelled("Creative rationale", direction.getCreativeRationale()),
                labelled("Reference asset ids", references.stream().map(r -> r.getAssetId().toString()).collect(Collectors.joining(", "))));
    }

    private static String feedbackText(List<CreativeDirectionFeedback> feedback, String note) {
        List<String> notes = new ArrayList<>(feedback.stream()
                .map(item -> "- (" + item.getSource().name().toLowerCase() + ") " + item.getFeedback())
                .toList());
        if (!isBlank(note)) {
            notes.add("- (creator) " + note.strip());
        }
        return String.join("\n", notes);
    }

    // ---------------------------------------------------------------- persistence helpers

    private static CreativeDirectionGeneration snapshot(UUID tenantId, UUID projectId, int round, CreativeContext context,
                                                        Integer durationSeconds, UUID userId, OffsetDateTime now) {
        CreativeContext.Idea idea = context.idea();
        return CreativeDirectionGeneration.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .round(round)
                .lockedIdeaId(context.lockedIdeaId())
                .ideaTitle(idea == null ? null : idea.title())
                .ideaConcept(idea == null ? null : idea.concept())
                .ideaTargetAudience(idea == null ? null : idea.targetAudience())
                .ideaCampaignAngle(idea == null ? null : idea.campaignAngle())
                .ideaKeyMessage(idea == null ? null : idea.keyMessage())
                .ideaTone(idea == null ? null : idea.tone())
                .briefText(context.brief() == null ? null : context.brief().briefText())
                .durationSeconds(durationSeconds)
                .createdBy(userId)
                .createdAt(now)
                .build();
    }

    private static CreativeDirection newDirection(UUID tenantId, UUID projectId, UUID generationId, int optionNumber,
                                                  int version, UUID revisedFromId, CreativeDirectionGenerationResult.Direction source,
                                                  boolean recommended, String recommendationReason,
                                                  CreativeDirectionReviewStatus status, OffsetDateTime now) {
        CreativeDirectionGenerationResult.VisualLanguage visual = source.visualLanguage();
        return CreativeDirection.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .generationId(generationId)
                .optionNumber(optionNumber)
                .version(version)
                .revisedFromId(revisedFromId)
                .title(source.title().strip())
                .creativeConcept(source.creativeConcept().strip())
                .directorsTreatment(source.directorsTreatment().strip())
                .storytellingStyle(source.storytellingStyle())
                .storyPeriod(visual == null ? null : visual.storyPeriod())
                .colorTreatment(visual == null ? null : visual.colorTreatment())
                .contrast(visual == null ? null : visual.contrast())
                .texture(visual == null ? null : visual.texture())
                .overallAesthetic(visual == null ? null : visual.overallAesthetic())
                .cinematographyPhilosophy(source.cinematographyPhilosophy())
                .emotionalJourney(source.emotionalJourney())
                .soundDirection(source.soundDirection())
                .signatureCreativeDevice(source.signatureCreativeDevice())
                .creativeRationale(source.creativeRationale())
                .recommended(recommended)
                .recommendationReason(recommendationReason)
                .reviewStatus(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Only asset ids that actually exist on the project's brief are kept; anything else the model
     * returned (an invented or mistyped id) is dropped and logged rather than stored. */
    private void saveReferences(UUID directionId, List<String> assetIds, Map<UUID, ReferenceAsset> available, OffsetDateTime now) {
        if (assetIds == null) {
            return;
        }
        assetIds.stream().distinct().forEach(raw -> {
            ReferenceAsset asset = parseUuid(raw).map(available::get).orElse(null);
            if (asset == null) {
                log.warn("Creative direction {} named reference asset '{}' which is not on this project's brief -- ignored", directionId, raw);
                return;
            }
            referenceRepository.save(CreativeDirectionReference.builder()
                    .creativeDirectionId(directionId)
                    .assetId(asset.assetId())
                    .mediaType(asset.mediaType())
                    .bucket(asset.bucket())
                    .objectKey(asset.objectKey())
                    .clientInstruction(asset.clientInstruction())
                    .referenceAnalysis(asset.analysis())
                    .createdAt(now)
                    .build());
        });
    }

    private Map<UUID, ReferenceAsset> liveAssets(UUID tenantId, UUID projectId) {
        try {
            return assetsById(creativePlanningClient.getCreativeContext(tenantId, projectId));
        } catch (RuntimeException ex) {
            log.warn("Reference media for project {} unavailable: {}", projectId, ex.getMessage());
            return Map.of();
        }
    }

    private static Map<UUID, ReferenceAsset> assetsById(CreativeContext context) {
        return context.safeReferences().stream()
                .collect(Collectors.toMap(ReferenceAsset::assetId, Function.identity(), (first, second) -> first));
    }

    private static <T> Map<UUID, List<T>> groupBy(Collection<T> items, Function<T, UUID> key) {
        return items.stream().collect(Collectors.groupingBy(key));
    }

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw.strip()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static String labelled(String label, String value) {
        return isBlank(value) ? null : label + ": " + value.strip();
    }

    private static String lines(String... parts) {
        return java.util.Arrays.stream(parts).filter(part -> !isBlank(part)).collect(Collectors.joining("\n"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
