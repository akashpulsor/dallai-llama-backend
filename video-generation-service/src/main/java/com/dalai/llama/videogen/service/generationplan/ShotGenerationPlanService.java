package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanAction;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanInterval;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.VideoGenJobView;
import com.dalai.llama.videogen.dto.generationplan.ActionCoverageView;
import com.dalai.llama.videogen.dto.generationplan.ContinuationFrameView;
import com.dalai.llama.videogen.dto.generationplan.PlanGenerationView;
import com.dalai.llama.videogen.dto.generationplan.PromptReviewFindingView;
import com.dalai.llama.videogen.dto.generationplan.PromptValidationView;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.dto.generationplan.ShotGenerationPlanView;
import com.dalai.llama.videogen.dto.generationplan.SourceTemporalActionView;
import com.dalai.llama.videogen.dto.generationplan.ValidationIssueView;
import com.dalai.llama.videogen.dto.generationplan.VideoDurationAssessmentView;
import com.dalai.llama.videogen.dto.generationplan.VideoGenerationSettingsView;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.dto.generationplan.VideoPromptDraftView;
import com.dalai.llama.videogen.dto.shotcontext.ReferenceFrame;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.ProjectConfigService;
import com.dalai.llama.videogen.service.PromptBuilderService;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.service.VideoAssetPersistenceService;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.VideoShotPromptService;
import com.dalai.llama.videogen.service.postproduction.PostProductionFrameClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The video studio: a shot's path from its approved plan to a source clip, one explicit step at a
 * time.
 *
 * <p>Generating used to be one fat step -- prepare chose a model, rewrote the prompt, compressed
 * it and costed it in one go, and the card then offered whatever that left behind. Every failure
 * along the way became a dead end on the page. Here each step is its own action the creator takes
 * and can retry, and none of them is a precondition for another except where the data truly is:
 * a timeline needs a chosen duration, and generating needs a prompt and settings the model can
 * actually render. Analysis, the timeline and the AI prompt are advice; a creator who writes their
 * own prompt can generate from it.
 *
 * <p>Nothing generates a video until {@link #generate} is called, and it sends the prompt it is
 * given, exactly.
 */
@Slf4j
@Service
public class ShotGenerationPlanService {

    private static final ShotContextAssemblyService.PrepareShotOverrides NO_OVERRIDES =
            new ShotContextAssemblyService.PrepareShotOverrides(null, null, null, null, null);

    private final ShotContextAssemblyService assemblyService;
    private final PreProductionServiceClient preProductionClient;
    private final PromptBuilderService promptBuilderService;
    private final ProjectConfigService projectConfigService;
    private final VideoShotPromptService videoShotPromptService;
    private final RequiredActionExtractor actionExtractor;
    private final VideoModelCapabilityService capabilityService;
    private final GenerationPlanLlm llm;
    private final ShotGenerationPlanStore store;
    private final PostProductionFrameClient postProductionFrameClient;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final ShotGenerationOrchestrator orchestrator;
    private final String defaultModel;

    public ShotGenerationPlanService(
            ShotContextAssemblyService assemblyService,
            PreProductionServiceClient preProductionClient,
            PromptBuilderService promptBuilderService,
            ProjectConfigService projectConfigService,
            VideoShotPromptService videoShotPromptService,
            RequiredActionExtractor actionExtractor,
            VideoModelCapabilityService capabilityService,
            GenerationPlanLlm llm,
            ShotGenerationPlanStore store,
            PostProductionFrameClient postProductionFrameClient,
            VideoAssetPersistenceService assetPersistenceService,
            ShotGenerationOrchestrator orchestrator,
            @Value("${video-gen.llm-gateway.default-video-model}") String defaultModel) {
        this.assemblyService = assemblyService;
        this.preProductionClient = preProductionClient;
        this.promptBuilderService = promptBuilderService;
        this.projectConfigService = projectConfigService;
        this.videoShotPromptService = videoShotPromptService;
        this.actionExtractor = actionExtractor;
        this.capabilityService = capabilityService;
        this.llm = llm;
        this.store = store;
        this.postProductionFrameClient = postProductionFrameClient;
        this.assetPersistenceService = assetPersistenceService;
        this.orchestrator = orchestrator;
        this.defaultModel = defaultModel;
    }

    // ------------------------------------------------------------------ read

    /**
     * The shot's plan. A shot never analysed gets a view built from its plan right now -- its
     * required actions and its model's options -- with no id and nothing stored, so the studio has
     * something real to show before the first click.
     */
    public ShotGenerationPlanView get(UUID tenantId, UUID projectId, UUID shotId) {
        return store.find(tenantId, shotId)
                .map(this::view)
                .orElseGet(() -> {
                    ShotContext shot = assemble(tenantId, projectId, shotId).shotContext();
                    String model = modelFor(shot);
                    return unsavedView(projectId, shotId, shot, model, actionExtractor.extract(shot));
                });
    }

    // ------------------------------------------------------------------ steps

    /** Extracts the required actions and asks for a duration and frame-rate assessment. */
    public ShotGenerationPlanView analyze(UUID tenantId, UUID projectId, UUID shotId) {
        ShotContextAssemblyService.AssembledShot assembled = assemble(tenantId, projectId, shotId);
        ShotContext shot = assembled.shotContext();
        String model = modelFor(shot);
        List<RequiredActionView> actions = actionExtractor.extract(shot);
        VideoModelCapabilitiesView capabilities = capabilityService.capabilities(model);

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("approvedCreativeDirection", creativeDirection(assembled));
        variables.put("shotJson", videoShotPromptService.shotJson(shot));
        variables.put("composedPrompt", composedPrompt(tenantId, projectId, assembled, model));
        variables.put("durationSeconds", plannedText(shot));
        variables.put("videoModelCapabilities", VideoModelCapabilityService.describe(capabilities));
        variables.put("requiredActions", actionsText(actions));
        GenerationPlanLlm.AssessmentAnswer answer = llm.assess(tenantId, projectId, variables);

        ShotGenerationPlanStore.PlanState state = ensure(tenantId, projectId, shotId, shot, model, actions);
        return view(store.saveAssessment(state.plan().getPlanId(), shot.shotRef(), model, planned(shot), actions, answer));
    }

    /**
     * The creator's choice of duration and frame rate. Refused only when the model cannot render
     * it; a duration shorter than the assessment's minimum is the creator's call, and the view says
     * so as a warning rather than changing their choice.
     */
    public ShotGenerationPlanView selectSettings(UUID tenantId, UUID projectId, UUID shotId,
                                                 int durationSeconds, Integer fps) {
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        VideoModelCapabilitiesView capabilities = capabilityService.capabilities(state.plan().getModelId());
        List<ValidationIssueView> errors = GenerationPlanValidator.settingsErrors(capabilities, durationSeconds, fps);
        if (!errors.isEmpty()) {
            throw VideoGenException.badRequest(errors.get(0).message());
        }
        return view(store.update(state.plan().getPlanId(), plan -> {
            plan.setGenerationDurationSeconds(durationSeconds);
            plan.setGenerationFps(fps);
        }));
    }

    /** Lays the plan's actions second by second across the selected duration. */
    public ShotGenerationPlanView buildTimeline(UUID tenantId, UUID projectId, UUID shotId) {
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        ShotGenerationPlan plan = state.plan();
        requireSettings(plan);
        ShotContextAssemblyService.AssembledShot assembled = assemble(tenantId, projectId, shotId);

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("approvedCreativeDirection", creativeDirection(assembled));
        variables.put("shotJson", videoShotPromptService.shotJson(assembled.shotContext()));
        variables.put("durationSeconds", plannedText(assembled.shotContext()));
        variables.put("generationDurationSeconds", String.valueOf(plan.getGenerationDurationSeconds()));
        variables.put("generationFps", fpsText(plan.getGenerationFps()));
        variables.put("requiredActions", actionsText(actionViews(state.actions())));
        List<GenerationPlanLlm.IntervalAnswer> intervals = llm.timeline(tenantId, projectId, variables);
        return view(store.saveTimeline(plan.getPlanId(), plan.getGenerationDurationSeconds(), plan.getGenerationFps(), intervals));
    }

    /**
     * Writes the AI recommendation for the selected settings. The creator's saved draft is a
     * separate value and is never touched here, so regenerating the recommendation can never cost
     * them an edit.
     */
    public ShotGenerationPlanView composePrompt(UUID tenantId, UUID projectId, UUID shotId) {
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        ShotGenerationPlan plan = state.plan();
        requireSettings(plan);
        ShotContextAssemblyService.AssembledShot assembled = assemble(tenantId, projectId, shotId);
        String model = plan.getModelId();
        int maxChars = promptBuilderService.maxPromptLengthFor(model);

        String written = videoShotPromptService.composeSourcePrompt(
                tenantId, projectId, assembled.shotContext(),
                composedPrompt(tenantId, projectId, assembled, model), maxChars,
                new VideoShotPromptService.SourceExecution(
                        plan.getGenerationDurationSeconds(), plan.getGenerationFps(),
                        timelineForCurrentSettings(state), creativeDirection(assembled),
                        plan.getContinuationFrameObjectKey() != null));
        String continuationKey = plan.getContinuationFrameObjectKey();
        return view(store.update(plan.getPlanId(), row -> {
            row.setAiRecommendedPrompt(written);
            row.setPromptDurationSeconds(row.getGenerationDurationSeconds());
            row.setPromptFps(row.getGenerationFps());
            row.setPromptContinuationObjectKey(continuationKey);
            row.setPromptComposedAt(java.time.OffsetDateTime.now());
        }));
    }

    public ShotGenerationPlanView saveDraft(UUID tenantId, UUID shotId, String prompt, int expectedRevision) {
        ShotGenerationPlanStore.PlanState state = store.require(tenantId, shotId);
        return view(store.saveDraft(state.plan().getPlanId(), prompt, expectedRevision));
    }

    /** Back to the AI recommendation: the draft is cleared, the recommendation itself untouched. */
    public ShotGenerationPlanView resetDraft(UUID tenantId, UUID shotId) {
        ShotGenerationPlanStore.PlanState state = store.require(tenantId, shotId);
        return view(store.update(state.plan().getPlanId(), plan -> {
            plan.setUserEditedPrompt(null);
            plan.setDraftRevision(plan.getDraftRevision() + 1);
            plan.setDraftSavedAt(java.time.OffsetDateTime.now());
        }));
    }

    /**
     * Checks a prompt against the plan without changing it: deterministic errors and warnings
     * first, then the AI review. The AI review failing does not fail the check -- the deterministic
     * half is still worth having -- and its findings are reported as possibilities, never verdicts.
     */
    public PromptValidationView validatePrompt(UUID tenantId, UUID projectId, UUID shotId, String prompt) {
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        ShotGenerationPlan plan = state.plan();
        int maxChars = promptBuilderService.maxPromptLengthFor(plan.getModelId());
        List<ValidationIssueView> errors = new ArrayList<>(GenerationPlanValidator.promptErrors(prompt, maxChars));
        errors.addAll(GenerationPlanValidator.settingsErrors(
                capabilityService.capabilities(plan.getModelId()), plan.getGenerationDurationSeconds(), plan.getGenerationFps()));
        List<ValidationIssueView> warnings = GenerationPlanValidator.promptWarnings(prompt, plan.getGenerationDurationSeconds());

        List<PromptReviewFindingView> findings = List.of();
        String reviewError = null;
        try {
            ShotContextAssemblyService.AssembledShot assembled = assemble(tenantId, projectId, shotId);
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("approvedCreativeDirection", creativeDirection(assembled));
            variables.put("shotJson", videoShotPromptService.shotJson(assembled.shotContext()));
            variables.put("requiredActions", actionsText(actionViews(state.actions())));
            variables.put("sourceActionTimeline", timelineForCurrentSettings(state));
            variables.put("generationDurationSeconds", plan.getGenerationDurationSeconds() == null
                    ? plannedText(assembled.shotContext()) : String.valueOf(plan.getGenerationDurationSeconds()));
            variables.put("generationFps", fpsText(plan.getGenerationFps()));
            variables.put("references", videoShotPromptService.describeReferences(
                    assembled.shotContext(), plan.getContinuationFrameObjectKey() != null));
            variables.put("maxChars", String.valueOf(maxChars));
            variables.put("editedPrompt", prompt);
            findings = llm.review(tenantId, projectId, variables);
        } catch (VideoGenException ex) {
            reviewError = ex.getMessage();
        }
        return new PromptValidationView(prompt == null ? 0 : prompt.length(), maxChars, errors, warnings, findings, reviewError);
    }

    /**
     * Attaches the last frame of the previous shot -- or of {@code sourceShotId} -- so this clip
     * opens where that one ended. The frame comes from post-production, which knows which cut of
     * that shot the film uses.
     */
    public ShotGenerationPlanView attachContinuationFrame(UUID tenantId, UUID projectId, UUID shotId, UUID sourceShotId) {
        UUID source = sourceShotId != null ? sourceShotId : previousShotId(tenantId, projectId, shotId);
        if (source.equals(shotId)) {
            throw VideoGenException.badRequest("A shot cannot continue from its own last frame.");
        }
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        PostProductionFrameClient.Frame frame = postProductionFrameClient.lastFrame(tenantId, projectId, source);
        return view(store.update(state.plan().getPlanId(), plan -> {
            plan.setContinuationSourceShotId(source);
            plan.setContinuationFrameBucket(frame.bucket());
            plan.setContinuationFrameObjectKey(frame.objectKey());
            plan.setContinuationFrameTimestampMs(frame.timestampMs());
        }));
    }

    public ShotGenerationPlanView detachContinuationFrame(UUID tenantId, UUID shotId) {
        ShotGenerationPlanStore.PlanState state = store.require(tenantId, shotId);
        return view(store.update(state.plan().getPlanId(), plan -> {
            plan.setContinuationSourceShotId(null);
            plan.setContinuationFrameBucket(null);
            plan.setContinuationFrameObjectKey(null);
            plan.setContinuationFrameTimestampMs(null);
        }));
    }

    /**
     * Generate Video. Submits {@code prompt} exactly as given, at the selected settings, through the
     * existing job lifecycle. Refuses only what would certainly fail or double-bill: settings the
     * model cannot render, a prompt it would not accept, or this shot already rendering.
     */
    public PlanGenerationView generate(TenantContext tenant, UUID projectId, UUID shotId, String prompt) {
        UUID tenantId = tenant.tenantId();
        ShotGenerationPlanStore.PlanState state = ensureFromPlan(tenantId, projectId, shotId);
        ShotGenerationPlan plan = state.plan();
        if (plan.getSubmittedJobId() != null) {
            VideoGenJobView previous = orchestrator.getJob(tenantId, plan.getSubmittedJobId());
            if (JobStatus.valueOf(previous.status()).isInFlight()) {
                throw VideoGenException.conflict("This shot is already being generated -- it will appear when it finishes.");
            }
        }
        List<ValidationIssueView> errors = new ArrayList<>(GenerationPlanValidator.settingsErrors(
                capabilityService.capabilities(plan.getModelId()), plan.getGenerationDurationSeconds(), plan.getGenerationFps()));
        errors.addAll(GenerationPlanValidator.promptErrors(prompt, promptBuilderService.maxPromptLengthFor(plan.getModelId())));
        if (!errors.isEmpty()) {
            throw VideoGenException.badRequest(errors.get(0).message());
        }

        ShotContextAssemblyService.AssembledShot assembled = assemble(tenantId, projectId, shotId);
        ReferenceFrame continuation = plan.getContinuationFrameObjectKey() == null ? null
                : new ReferenceFrame(ReferenceKind.PRIOR_SHOT_LAST_FRAME, plan.getContinuationFrameBucket(),
                        plan.getContinuationFrameObjectKey());
        ShotGenerationOrchestrator.PreparedShot submitted = orchestrator.submitReviewedPrompt(tenant, projectId, assembled,
                new ShotGenerationOrchestrator.ReviewedSubmission(plan.getModelId(), plan.getGenerationDurationSeconds(),
                        plan.getGenerationFps(), prompt, continuation));
        store.update(plan.getPlanId(), row -> {
            row.setSubmittedJobId(submitted.job().getJobId());
            row.setSubmittedAt(java.time.OffsetDateTime.now());
        });
        log.info("Generating from the video studio shotId={} jobId={} duration={}s fps={} chars={} continuation={}",
                shotId, submitted.job().getJobId(), plan.getGenerationDurationSeconds(), plan.getGenerationFps(),
                prompt.length(), continuation != null);
        return new PlanGenerationView(submitted.prompt().getPromptId(), orchestrator.getJob(tenantId, submitted.job().getJobId()));
    }

    // ------------------------------------------------------------------ helpers

    private ShotContextAssemblyService.AssembledShot assemble(UUID tenantId, UUID projectId, UUID shotId) {
        return assemblyService.assemble(tenantId, projectId, shotId, NO_OVERRIDES);
    }

    /** The plan if the shot has one, else a new one from the shot as it stands. */
    private ShotGenerationPlanStore.PlanState ensureFromPlan(UUID tenantId, UUID projectId, UUID shotId) {
        return store.find(tenantId, shotId).orElseGet(() -> {
            ShotContext shot = assemble(tenantId, projectId, shotId).shotContext();
            return ensure(tenantId, projectId, shotId, shot, modelFor(shot), actionExtractor.extract(shot));
        });
    }

    private ShotGenerationPlanStore.PlanState ensure(UUID tenantId, UUID projectId, UUID shotId, ShotContext shot,
                                                     String model, List<RequiredActionView> actions) {
        return store.ensure(tenantId, projectId, shotId, shot.shotRef(), model, planned(shot), actions);
    }

    /** The project's pinned model, else the service default -- the same model the shot would be
     * generated on, so the options shown are the ones that will apply. */
    private String modelFor(ShotContext shot) {
        String pinned = shot.technical() == null ? null : shot.technical().targetModel();
        return pinned == null || pinned.isBlank() ? defaultModel : pinned;
    }

    private String composedPrompt(UUID tenantId, UUID projectId, ShotContextAssemblyService.AssembledShot assembled, String model) {
        FeatureFlags flags = projectConfigService.getEffectiveFlags(tenantId, projectId)
                .withOverride(assembled.featureFlagOverrides());
        return promptBuilderService.buildPrompt(assembled.shotContext(), flags, model).positive();
    }

    private static String creativeDirection(ShotContextAssemblyService.AssembledShot assembled) {
        String block = assembled.sources() == null ? null : assembled.sources().approvedCreativeDirection();
        return block == null || block.isBlank() ? VideoShotPromptService.SourceExecution.NO_CREATIVE_DIRECTION : block;
    }

    private void requireSettings(ShotGenerationPlan plan) {
        if (plan.getGenerationDurationSeconds() == null) {
            throw VideoGenException.badRequest("Choose a generation duration first.");
        }
    }

    /** The approved timeline, if it was built for the settings now selected. A timeline for other
     * settings would put the prompt on the wrong clock, so it is not passed at all. */
    private String timelineForCurrentSettings(ShotGenerationPlanStore.PlanState state) {
        ShotGenerationPlan plan = state.plan();
        boolean current = !state.intervals().isEmpty()
                && Objects.equals(plan.getTimelineDurationSeconds(), plan.getGenerationDurationSeconds())
                && Objects.equals(plan.getTimelineFps(), plan.getGenerationFps());
        return current ? timelineText(state.intervals()) : VideoShotPromptService.SourceExecution.NO_TIMELINE;
    }

    private UUID previousShotId(UUID tenantId, UUID projectId, UUID shotId) {
        List<PreProductionViews.ShotBundleView> shots = preProductionClient.getPrepareBundle(tenantId, projectId)
                .map(PreProductionViews.PrepareBundleView::shots)
                .orElseThrow(() -> VideoGenException.upstream("pre-production-service returned no shots for project " + projectId));
        for (int i = 0; i < shots.size(); i++) {
            if (shots.get(i).shot() != null && shotId.equals(shots.get(i).shot().id())) {
                if (i == 0) {
                    throw VideoGenException.badRequest("This is the first shot -- there is no previous clip to continue from.");
                }
                return shots.get(i - 1).shot().id();
            }
        }
        throw VideoGenException.notFound("No shot " + shotId + " in project " + projectId);
    }

    static String actionsText(List<RequiredActionView> actions) {
        StringBuilder text = new StringBuilder();
        for (RequiredActionView action : actions) {
            text.append(action.actionId()).append(" [").append(action.kind());
            if (action.fixedSeconds() != null) {
                text.append(", FIXED ").append(action.fixedSeconds().stripTrailingZeros().toPlainString()).append("s");
            }
            if (action.dependsOnActionId() != null) {
                text.append(", after ").append(action.dependsOnActionId());
            }
            text.append("] ").append(action.description()).append('\n');
        }
        return text.toString().strip();
    }

    static String timelineText(List<ShotGenerationPlanInterval> intervals) {
        StringBuilder text = new StringBuilder();
        for (ShotGenerationPlanInterval interval : intervals) {
            text.append(seconds(interval.getStartSeconds())).append('-').append(seconds(interval.getEndSeconds()))
                    .append("s [").append(interval.getActionId()).append("] ").append(interval.getAction());
            if (interval.getSubjectState() != null && !interval.getSubjectState().isBlank()) {
                text.append(" Subject: ").append(interval.getSubjectState());
            }
            if (interval.getCameraBehavior() != null && !interval.getCameraBehavior().isBlank()) {
                text.append(" Camera: ").append(interval.getCameraBehavior());
            }
            if (Boolean.TRUE.equals(interval.getHoldRequired())) {
                text.append(" (hold)");
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    private static String seconds(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal planned(ShotContext shot) {
        Integer seconds = shot.technical() == null ? null : shot.technical().durationSeconds();
        return seconds == null ? null : BigDecimal.valueOf(seconds).setScale(3, RoundingMode.HALF_UP);
    }

    private static String plannedText(ShotContext shot) {
        Integer seconds = shot.technical() == null ? null : shot.technical().durationSeconds();
        return seconds == null ? "unspecified" : String.valueOf(seconds);
    }

    private static String fpsText(Integer fps) {
        return fps == null ? "the model's native frame rate" : String.valueOf(fps);
    }

    // ------------------------------------------------------------------ views

    private ShotGenerationPlanView view(ShotGenerationPlanStore.PlanState state) {
        ShotGenerationPlan plan = state.plan();
        VideoModelCapabilitiesView capabilities = capabilityService.capabilities(plan.getModelId());
        List<RequiredActionView> actions = actionViews(state.actions());
        VideoDurationAssessmentView assessment = plan.getAssessedAt() == null ? null : new VideoDurationAssessmentView(
                plan.getShorterGenerationSuitable(),
                plan.getMinimumViableDurationSeconds(),
                plan.getRecommendedDurationSeconds(),
                plan.getRecommendedFps(),
                plan.getAssessmentReasoning(),
                state.coverage().stream().map(c -> new ActionCoverageView(
                        c.getActionId(), c.getStartSeconds(), c.getEndSeconds(), Boolean.TRUE.equals(c.getPreserved()))).toList(),
                state.risks().stream().map(r -> r.getRisk()).toList(),
                plan.getAssessedAt());
        List<SourceTemporalActionView> timeline = state.intervals().stream().map(i -> new SourceTemporalActionView(
                i.getStartSeconds(), i.getEndSeconds(), i.getActionId(), i.getAction(), i.getSubjectState(),
                i.getCameraBehavior(), Boolean.TRUE.equals(i.getHoldRequired()))).toList();

        ContinuationFrameView continuation = plan.getContinuationFrameObjectKey() == null ? null
                : new ContinuationFrameView(plan.getContinuationSourceShotId(), plan.getContinuationFrameObjectKey(),
                        plan.getContinuationFrameTimestampMs(),
                        assetPersistenceService.presignedUrl(plan.getContinuationFrameBucket(), plan.getContinuationFrameObjectKey()));

        return new ShotGenerationPlanView(
                plan.getPlanId(), plan.getProjectId(), plan.getShotId(), plan.getShotRef(), plan.getModelId(),
                plan.getPlannedDurationSeconds(), capabilities, actions, assessment,
                GenerationPlanValidator.assessment(actions, assessment, capabilities),
                settingsView(capabilities, plan.getGenerationDurationSeconds(), plan.getGenerationFps(), assessment),
                plan.getTimelineDurationSeconds(), plan.getTimelineFps(), timeline,
                GenerationPlanValidator.timeline(actions, timeline, plan.getTimelineDurationSeconds()),
                new VideoPromptDraftView(plan.getAiRecommendedPrompt(), plan.getUserEditedPrompt(), plan.getDraftRevision(),
                        plan.getPromptDurationSeconds(), plan.getPromptFps(), plan.getPromptContinuationObjectKey(),
                        plan.getPromptComposedAt(), plan.getDraftSavedAt(),
                        promptBuilderService.maxPromptLengthFor(plan.getModelId())),
                continuation, plan.getSubmittedJobId(), plan.getSubmittedAt());
    }

    private ShotGenerationPlanView unsavedView(UUID projectId, UUID shotId, ShotContext shot, String model,
                                               List<RequiredActionView> actions) {
        VideoModelCapabilitiesView capabilities = capabilityService.capabilities(model);
        return new ShotGenerationPlanView(null, projectId, shotId, shot.shotRef(), model, planned(shot), capabilities,
                actions, null, List.of(), settingsView(capabilities, null, null, null), null, null, List.of(), List.of(),
                new VideoPromptDraftView(null, null, 0, null, null, null, null, null,
                        promptBuilderService.maxPromptLengthFor(model)),
                null, null, null);
    }

    private static VideoGenerationSettingsView settingsView(VideoModelCapabilitiesView capabilities, Integer duration,
                                                            Integer fps, VideoDurationAssessmentView assessment) {
        return new VideoGenerationSettingsView(duration, fps,
                duration == null ? List.of() : GenerationPlanValidator.settingsErrors(capabilities, duration, fps),
                GenerationPlanValidator.settingsWarnings(duration, assessment));
    }

    private static List<RequiredActionView> actionViews(List<ShotGenerationPlanAction> rows) {
        return rows.stream().map(a -> new RequiredActionView(
                a.getActionId(), a.getKind(), a.getDescription(), a.getFixedSeconds(), a.getDependsOnActionId())).toList();
    }
}
