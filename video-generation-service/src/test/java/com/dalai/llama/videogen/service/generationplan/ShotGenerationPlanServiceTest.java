package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.ShotActionKind;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanAction;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanInterval;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.VideoGenJobView;
import com.dalai.llama.videogen.dto.generationplan.PromptValidationView;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.service.BuiltPrompt;
import com.dalai.llama.videogen.service.GenerationControlsService;
import com.dalai.llama.videogen.service.ProjectConfigService;
import com.dalai.llama.videogen.service.PromptBuilderService;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.service.VideoAssetPersistenceService;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.VideoShotPromptService;
import com.dalai.llama.videogen.service.postproduction.PostProductionClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import com.dalai.llama.videogen.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The studio's contract with the creator: nothing renders until Generate Video, Generate sends
 * exactly the text it was given, and an AI step never quietly overwrites a decision they made.
 */
class ShotGenerationPlanServiceTest {

    private static final String MODEL = "bytedance/seedance-2.0/fast";
    private static final String CREATIVE_DIRECTION = "APPROVED TREATMENT: warm, handheld, natural light.";

    private final ShotContextAssemblyService assembly = mock(ShotContextAssemblyService.class);
    private final PreProductionServiceClient preProduction = mock(PreProductionServiceClient.class);
    private final PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
    private final ProjectConfigService projectConfig = mock(ProjectConfigService.class);
    private final VideoShotPromptService promptWriter = mock(VideoShotPromptService.class);
    private final VideoModelCapabilityService capabilities = mock(VideoModelCapabilityService.class);
    private final GenerationPlanLlm llm = mock(GenerationPlanLlm.class);
    private final ShotGenerationPlanStore store = mock(ShotGenerationPlanStore.class);
    private final PostProductionClient postProduction = mock(PostProductionClient.class);
    private final VideoAssetPersistenceService assets = mock(VideoAssetPersistenceService.class);
    private final ShotGenerationOrchestrator orchestrator = mock(ShotGenerationOrchestrator.class);
    private final GenerationControlsService controls = mock(GenerationControlsService.class);

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final TenantContext context = new TenantContext(tenant, UUID.randomUUID());

    private ShotGenerationPlanService service;
    private ShotGenerationPlan plan;

    @BeforeEach
    void setUp() {
        service = new ShotGenerationPlanService(assembly, preProduction, promptBuilder, projectConfig, promptWriter,
                new RequiredActionExtractor(), capabilities, llm, store, postProduction, assets, orchestrator, controls, MODEL);
        when(controls.forProject(any(), any())).thenReturn(GenerationControlsView.DEFAULTS);

        ShotContext shot = mock(ShotContext.class);
        when(shot.shotRef()).thenReturn("shot-01-002");
        when(shot.narrative()).thenReturn(new Narrative("Ravi kneels. He tightens the valve.", null, null, null));
        ShotContextAssemblyService.ShotPromptSources sources = new ShotContextAssemblyService.ShotPromptSources(
                shotId, null, null, null, null, OffsetDateTime.now(), List.of(), CREATIVE_DIRECTION);
        when(assembly.assemble(eq(tenant), eq(project), eq(shotId), any()))
                .thenReturn(new ShotContextAssemblyService.AssembledShot(shot, null, null, sources));

        when(capabilities.capabilities(MODEL)).thenReturn(new VideoModelCapabilitiesView(
                MODEL, IntStream.rangeClosed(4, 15).boxed().toList(), List.of(24), true));
        when(promptBuilder.maxPromptLengthFor(MODEL)).thenReturn(4000);
        when(promptBuilder.buildPrompt(any(), any(), anyString())).thenReturn(new BuiltPrompt("composed spec", "blurry"));
        when(projectConfig.getEffectiveFlags(tenant, project)).thenReturn(new FeatureFlags(FlagState.OFF, FlagState.OFF));

        plan = ShotGenerationPlan.builder()
                .planId(UUID.randomUUID()).tenantId(tenant).projectId(project).shotId(shotId).modelId(MODEL)
                .generationDurationSeconds(6).generationFps(24).draftRevision(3)
                .userEditedPrompt("the creator's own edit")
                .build();
        stubPlan(List.of());
        when(store.update(eq(plan.getPlanId()), any())).thenAnswer(call -> {
            Consumer<ShotGenerationPlan> change = call.getArgument(1);
            change.accept(plan);
            return state(List.of());
        });
    }

    private ShotGenerationPlanStore.PlanState state(List<ShotGenerationPlanInterval> intervals) {
        return new ShotGenerationPlanStore.PlanState(plan, List.of(
                ShotGenerationPlanAction.builder().actionId("OPEN").kind(ShotActionKind.OPENING_STATE).description("open").build(),
                ShotGenerationPlanAction.builder().actionId("END").kind(ShotActionKind.ENDING_STATE).description("end").dependsOnActionId("OPEN").build()),
                List.of(), List.of(), intervals);
    }

    private void stubPlan(List<ShotGenerationPlanInterval> intervals) {
        when(store.find(tenant, shotId)).thenReturn(Optional.of(state(intervals)));
        when(store.require(tenant, shotId)).thenReturn(state(intervals));
        when(store.ensure(any(), any(), any(), any(), any(), any(), any())).thenReturn(state(intervals));
    }

    private void orchestratorAccepts() {
        VideoGenJob job = VideoGenJob.builder().jobId(UUID.randomUUID()).build();
        ShotPrompt prompt = ShotPrompt.builder().promptId(UUID.randomUUID()).build();
        when(orchestrator.submitReviewedPrompt(any(), any(), any(), any()))
                .thenReturn(new ShotGenerationOrchestrator.PreparedShot(job, prompt, null, BigDecimal.ONE, List.of(), MODEL, null));
        when(orchestrator.getJob(eq(tenant), any())).thenReturn(jobView("QUEUED"));
    }

    private static VideoGenJobView jobView(String status) {
        return new VideoGenJobView(UUID.randomUUID(), "shot-01-002", status, "APPROVED", null, null, null, false, null, null, 6, 6, null);
    }

    // ---------------------------------------------------------------- generate

    @Test
    void generateSubmitsTheCreatorsPromptExactlyAtTheSelectedSettings() {
        orchestratorAccepts();
        String exact = "0-1s: Ravi kneels by the sink.  1-6s: he tightens the valve; water stops.\n";

        service.generate(context, project, shotId, exact);

        ArgumentCaptor<ShotGenerationOrchestrator.ReviewedSubmission> sent =
                ArgumentCaptor.forClass(ShotGenerationOrchestrator.ReviewedSubmission.class);
        verify(orchestrator).submitReviewedPrompt(eq(context), eq(project), any(), sent.capture());
        // Byte for byte, whitespace included: not the recommendation, not a "cleaned" version.
        assertThat(sent.getValue().prompt()).isEqualTo(exact);
        assertThat(sent.getValue().generationDurationSeconds()).isEqualTo(6);
        assertThat(sent.getValue().generationFps()).isEqualTo(24);
        assertThat(sent.getValue().continuationFrame()).isNull();
        assertThat(plan.getSubmittedJobId()).isNotNull();
    }

    @Test
    void anAttachedLastFrameTravelsWithTheSubmissionAsThePriorShotsFrame() {
        orchestratorAccepts();
        plan.setContinuationFrameBucket("postprod");
        plan.setContinuationFrameObjectKey("shot-frames/prev/v2/last_frame/last.jpg");

        service.generate(context, project, shotId, "0-6s: he stands.");

        ArgumentCaptor<ShotGenerationOrchestrator.ReviewedSubmission> sent =
                ArgumentCaptor.forClass(ShotGenerationOrchestrator.ReviewedSubmission.class);
        verify(orchestrator).submitReviewedPrompt(any(), any(), any(), sent.capture());
        assertThat(sent.getValue().continuationFrame().kind()).isEqualTo(ReferenceKind.PRIOR_SHOT_LAST_FRAME);
        assertThat(sent.getValue().continuationFrame().objectKey()).isEqualTo("shot-frames/prev/v2/last_frame/last.jpg");
    }

    @Test
    void settingsTheModelCannotRenderAreRefusedBeforeAnythingIsSpent() {
        plan.setGenerationDurationSeconds(3);

        assertThatThrownBy(() -> service.generate(context, project, shotId, "0-3s: he stands."))
                .isInstanceOf(VideoGenException.class)
                .hasMessageContaining("cannot generate 3s");
        verify(orchestrator, never()).submitReviewedPrompt(any(), any(), any(), any());
    }

    @Test
    void aShotAlreadyRenderingIsNotQueuedAndBilledASecondTime() {
        plan.setSubmittedJobId(UUID.randomUUID());
        when(orchestrator.getJob(tenant, plan.getSubmittedJobId())).thenReturn(jobView("PROCESSING"));

        assertThatThrownBy(() -> service.generate(context, project, shotId, "0-6s: he stands."))
                .hasMessageContaining("already being generated");
        verify(orchestrator, never()).submitReviewedPrompt(any(), any(), any(), any());
    }

    @Test
    void noOtherStepEverStartsARender() {
        when(llm.assess(any(), any(), any())).thenReturn(new GenerationPlanLlm.AssessmentAnswer(
                true, new BigDecimal("5"), 6, 24, "fits", List.of(), List.of()));
        when(store.saveAssessment(any(), any(), any(), any(), any(), any())).thenReturn(state(List.of()));
        when(llm.timeline(any(), any(), any())).thenReturn(List.of());
        when(store.saveTimeline(any(), anyInt(), any(), any())).thenReturn(state(List.of()));
        when(promptWriter.composeSourcePrompt(any(), any(), any(), anyString(), anyInt(), any())).thenReturn("0-6s: ...");
        when(store.saveDraft(any(), anyString(), anyInt())).thenReturn(state(List.of()));

        service.analyze(tenant, project, shotId);
        service.selectSettings(tenant, project, shotId, 6, 24);
        service.buildTimeline(tenant, project, shotId);
        service.composePrompt(tenant, project, shotId);
        service.saveDraft(tenant, shotId, "edited", 3);
        service.resetDraft(tenant, shotId);

        verify(orchestrator, never()).submitReviewedPrompt(any(), any(), any(), any());
        verify(orchestrator, never()).approve(any(), any());
    }

    // ---------------------------------------------------------------- prompt

    @Test
    void regeneratingTheRecommendationNeverTouchesTheCreatorsDraft() {
        when(promptWriter.composeSourcePrompt(any(), any(), any(), anyString(), anyInt(), any())).thenReturn("new AI text");

        service.composePrompt(tenant, project, shotId);

        assertThat(plan.getAiRecommendedPrompt()).isEqualTo("new AI text");
        assertThat(plan.getUserEditedPrompt()).isEqualTo("the creator's own edit");
        assertThat(plan.getDraftRevision()).isEqualTo(3);
        assertThat(plan.getPromptDurationSeconds()).isEqualTo(6);
    }

    @Test
    void aTimelineBuiltForOtherSettingsIsNotUsedAndTheCreativeDirectionIs() {
        plan.setTimelineDurationSeconds(8);
        plan.setTimelineFps(24);
        stubPlan(List.of(ShotGenerationPlanInterval.builder().startSeconds(BigDecimal.ZERO).endSeconds(new BigDecimal("8"))
                .actionId("OPEN").action("old clock").holdRequired(false).build()));
        when(promptWriter.composeSourcePrompt(any(), any(), any(), anyString(), anyInt(), any())).thenReturn("text");

        service.composePrompt(tenant, project, shotId);

        ArgumentCaptor<VideoShotPromptService.SourceExecution> execution =
                ArgumentCaptor.forClass(VideoShotPromptService.SourceExecution.class);
        verify(promptWriter).composeSourcePrompt(eq(tenant), eq(project), any(), eq("composed spec"), eq(4000), execution.capture());
        assertThat(execution.getValue().sourceActionTimeline()).isEqualTo(VideoShotPromptService.SourceExecution.NO_TIMELINE);
        assertThat(execution.getValue().approvedCreativeDirection()).isEqualTo(CREATIVE_DIRECTION);
        assertThat(execution.getValue().generationDurationSeconds()).isEqualTo(6);
    }

    @Test
    void theTimelineForTheCurrentSettingsIsPassedSecondBySecond() {
        plan.setTimelineDurationSeconds(6);
        plan.setTimelineFps(24);
        stubPlan(List.of(
                ShotGenerationPlanInterval.builder().startSeconds(BigDecimal.ZERO).endSeconds(BigDecimal.ONE)
                        .actionId("OPEN").action("Ravi kneels.").holdRequired(false).build(),
                ShotGenerationPlanInterval.builder().startSeconds(BigDecimal.ONE).endSeconds(new BigDecimal("6"))
                        .actionId("END").action("Water stops.").cameraBehavior("static").holdRequired(true).build()));
        when(promptWriter.composeSourcePrompt(any(), any(), any(), anyString(), anyInt(), any())).thenReturn("text");

        service.composePrompt(tenant, project, shotId);

        ArgumentCaptor<VideoShotPromptService.SourceExecution> execution =
                ArgumentCaptor.forClass(VideoShotPromptService.SourceExecution.class);
        verify(promptWriter).composeSourcePrompt(any(), any(), any(), anyString(), anyInt(), execution.capture());
        assertThat(execution.getValue().sourceActionTimeline())
                .isEqualTo("0.0-1.0s [OPEN] Ravi kneels.\n1.0-6.0s [END] Water stops. Camera: static (hold)");
    }

    @Test
    void anAiReviewThatFailsStillReturnsTheDeterministicResult() {
        when(llm.review(any(), any(), any())).thenThrow(VideoGenException.upstream("model unavailable"));

        PromptValidationView result = service.validatePrompt(tenant, project, shotId, "x".repeat(4001));

        assertThat(result.errors()).extracting("code").contains("OVER_CHARACTER_LIMIT");
        assertThat(result.aiReviewError()).contains("model unavailable");
        assertThat(result.aiFindings()).isEmpty();
    }

    // ---------------------------------------------------------------- analysis

    @Test
    void theAssessmentIsAskedWithNumberedActionsTheModelsRealOptionsAndTheCreativeDirection() {
        when(llm.assess(any(), any(), any())).thenReturn(new GenerationPlanLlm.AssessmentAnswer(
                true, new BigDecimal("5"), 6, 24, "fits", List.of(), List.of()));
        when(store.saveAssessment(any(), any(), any(), any(), any(), any())).thenReturn(state(List.of()));

        service.analyze(tenant, project, shotId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(llm).assess(eq(tenant), eq(project), variables.capture());
        assertThat(variables.getValue().get("requiredActions"))
                .contains("A1 [ACTION, after OPEN] Ravi kneels.")
                .contains("A2 [ACTION, after A1] He tightens the valve.");
        assertThat(variables.getValue().get("approvedCreativeDirection")).isEqualTo(CREATIVE_DIRECTION);
        assertThat(variables.getValue()).containsKeys("shotJson", "composedPrompt", "durationSeconds", "videoModelCapabilities");
    }

    // ---------------------------------------------------------------- continuation

    private static PostProductionClient.FrameRequest ready(UUID shot) {
        return new PostProductionClient.FrameRequest(null, "COMPLETED", null,
                List.of(new PostProductionClient.Frame(shot, "postprod", "shot-frames/p/last.jpg", 143L, 4767L)));
    }

    @Test
    void theLastFrameComesFromTheShotBeforeThisOneInTheFilm() {
        UUID previous = UUID.randomUUID();
        PreProductionViews.PrepareBundleView bundle = bundle(previous, shotId);
        when(preProduction.getPrepareBundle(tenant, project)).thenReturn(Optional.of(bundle));
        when(postProduction.requestLastFrame(tenant, project, previous)).thenReturn(ready(previous));

        service.attachContinuationFrame(tenant, project, shotId, null);

        assertThat(plan.getContinuationSourceShotId()).isEqualTo(previous);
        assertThat(plan.getContinuationFrameObjectKey()).isEqualTo("shot-frames/p/last.jpg");
        assertThat(plan.getContinuationFrameTimestampMs()).isEqualTo(4767L);
        assertThat(plan.getContinuationRequestId()).isNull();
    }

    @Test
    void aFrameStillBeingTakenIsRecordedAsPendingAndResolvedWhenThePlanIsRead() {
        UUID previous = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(postProduction.requestLastFrame(tenant, project, previous))
                .thenReturn(new PostProductionClient.FrameRequest(requestId, "QUEUED", null, List.of()));

        service.attachContinuationFrame(tenant, project, shotId, previous);

        assertThat(plan.getContinuationRequestId()).isEqualTo(requestId);
        assertThat(plan.getContinuationFrameObjectKey()).isNull();

        when(postProduction.status(tenant, requestId)).thenReturn(ready(previous));
        service.get(tenant, project, shotId);

        assertThat(plan.getContinuationFrameObjectKey()).isEqualTo("shot-frames/p/last.jpg");
        assertThat(plan.getContinuationRequestId()).isNull();
    }

    @Test
    void aPreviousShotWithNoVideoFailsTheFrameWithItsReason() {
        UUID previous = UUID.randomUUID();
        when(postProduction.requestLastFrame(tenant, project, previous))
                .thenReturn(new PostProductionClient.FrameRequest(UUID.randomUUID(), "FAILED", "This shot has no video yet", List.of()));

        service.attachContinuationFrame(tenant, project, shotId, previous);

        assertThat(plan.getContinuationError()).contains("no video yet");
        assertThat(plan.getContinuationRequestId()).isNull();
    }

    @Test
    void generatingWhileTheChosenFrameIsStillBeingTakenWaitsRatherThanDroppingIt() {
        plan.setContinuationRequestId(UUID.randomUUID());
        when(postProduction.status(tenant, plan.getContinuationRequestId()))
                .thenReturn(new PostProductionClient.FrameRequest(plan.getContinuationRequestId(), "PROCESSING", null, List.of()));

        assertThatThrownBy(() -> service.generate(context, project, shotId, "0-6s he stands."))
                .hasMessageContaining("still being taken");
        verify(orchestrator, never()).submitReviewedPrompt(any(), any(), any(), any());
    }

    @Test
    void withAutoAttachOnTheFirstShotStillGeneratesWithoutAFrame() {
        orchestratorAccepts();
        when(controls.forProject(any(), any())).thenReturn(new GenerationControlsView(false, true, true, true, true, true, true));
        PreProductionViews.PrepareBundleView bundle = bundle(shotId, UUID.randomUUID());
        when(preProduction.getPrepareBundle(tenant, project)).thenReturn(Optional.of(bundle));

        service.generate(context, project, shotId, "0-6s he stands.");

        verify(orchestrator).submitReviewedPrompt(any(), any(), any(), any());
        verify(postProduction, never()).requestLastFrame(any(), any(), any());
    }

    @Test
    void withAutoAttachOnAReadyFrameFromThePreviousShotIsSentWithTheRender() {
        orchestratorAccepts();
        when(controls.forProject(any(), any())).thenReturn(new GenerationControlsView(false, true, true, true, true, true, true));
        UUID previous = UUID.randomUUID();
        PreProductionViews.PrepareBundleView bundle = bundle(previous, shotId);
        when(preProduction.getPrepareBundle(tenant, project)).thenReturn(Optional.of(bundle));
        when(postProduction.requestLastFrame(tenant, project, previous)).thenReturn(ready(previous));

        service.generate(context, project, shotId, "0-6s he stands.");

        ArgumentCaptor<ShotGenerationOrchestrator.ReviewedSubmission> sent =
                ArgumentCaptor.forClass(ShotGenerationOrchestrator.ReviewedSubmission.class);
        verify(orchestrator).submitReviewedPrompt(any(), any(), any(), sent.capture());
        assertThat(sent.getValue().continuationFrame().objectKey()).isEqualTo("shot-frames/p/last.jpg");
    }

    @Test
    void withDuplicateProtectionSwitchedOffTheCreatorMayQueueAgain() {
        orchestratorAccepts();
        when(controls.forProject(any(), any())).thenReturn(new GenerationControlsView(false, true, true, false, false, true, true));
        plan.setSubmittedJobId(UUID.randomUUID());
        when(orchestrator.getJob(tenant, plan.getSubmittedJobId())).thenReturn(jobView("PROCESSING"));

        service.generate(context, project, shotId, "0-6s he stands.");

        verify(orchestrator).submitReviewedPrompt(any(), any(), any(), any());
    }

    @Test
    void theFirstShotHasNothingToContinueFrom() {
        PreProductionViews.PrepareBundleView bundle = bundle(shotId, UUID.randomUUID());
        when(preProduction.getPrepareBundle(tenant, project)).thenReturn(Optional.of(bundle));

        assertThatThrownBy(() -> service.attachContinuationFrame(tenant, project, shotId, null))
                .hasMessageContaining("first shot");
        verify(postProduction, never()).requestLastFrame(any(), any(), any());
    }

    private static PreProductionViews.PrepareBundleView bundle(UUID... shotIds) {
        List<PreProductionViews.ShotBundleView> shots = java.util.Arrays.stream(shotIds).map(id -> {
            PreProductionViews.ShotView view = mock(PreProductionViews.ShotView.class);
            when(view.id()).thenReturn(id);
            return new PreProductionViews.ShotBundleView(view, List.of(), null, null, List.of(), null, null);
        }).toList();
        return new PreProductionViews.PrepareBundleView(null, null, null, List.of(), List.of(), shots);
    }
}
