package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.ShotPromptReference;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.ReferenceFrame;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.kafka.VideoGenerationRequestedEvent;
import com.dalai.llama.videogen.kafka.VideoGenerationRequestedPublisher;
import com.dalai.llama.videogen.repository.FoleyCueRepository;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.repository.VideoGenJobDialogueBeatRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitChoice;
import com.dalai.llama.videogen.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Generate Video from the studio goes through the same job lifecycle as every other render, and
 * differs only where the creator has already decided: their text is what is sent, and their
 * duration is what is rendered.
 */
class ReviewedPromptSubmissionTest {

    private final PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
    private final PromptCompressionService compression = mock(PromptCompressionService.class);
    private final CostEstimationService costs = mock(CostEstimationService.class);
    private final ProjectConfigService projectConfig = mock(ProjectConfigService.class);
    private final VideoGenJobRepository jobs = mock(VideoGenJobRepository.class);
    private final ShotPromptRepository prompts = mock(ShotPromptRepository.class);
    private final ShotPromptReferenceRepository references = mock(ShotPromptReferenceRepository.class);
    private final FoleyCueService foley = mock(FoleyCueService.class);
    private final VideoGenJobPersistenceService jobPersistence = mock(VideoGenJobPersistenceService.class);
    private final BeatDubbingService dubbing = mock(BeatDubbingService.class);
    private final VideoShotPromptService promptWriter = mock(VideoShotPromptService.class);
    private final VideoGenerationRequestedPublisher publisher = mock(VideoGenerationRequestedPublisher.class);
    private final GenerationControlsService controls = mock(GenerationControlsService.class);
    private final com.dalai.llama.videogen.service.render.RenderDurationPolicy durationPolicy =
            mock(com.dalai.llama.videogen.service.render.RenderDurationPolicy.class);

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final AtomicReference<VideoGenJob> savedJob = new AtomicReference<>();

    private ShotGenerationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ShotGenerationOrchestrator(mock(ModelRecommendationService.class), promptBuilder,
                mock(DialogueFitService.class), compression, foley, costs, mock(VideoGenDispatchService.class),
                projectConfig, jobs, prompts, references, mock(VideoGenJobDialogueBeatRepository.class),
                mock(FoleyCueRepository.class), mock(VideoAssetPersistenceService.class), jobPersistence, dubbing,
                durationPolicy, new com.dalai.llama.videogen.service.render.ClipFinishing(dubbing,
                        mock(BackgroundMusicMixService.class), jobs, mock(com.dalai.llama.videogen.service.postproduction.PostProductionClient.class)),
                promptWriter, publisher, controls, "bytedance/seedance-2.0/fast");
        when(durationPolicy.clamp(anyString(), any())).thenAnswer(call -> call.getArgument(1));

        when(projectConfig.getEffectiveFlags(tenant, project)).thenReturn(new FeatureFlags(FlagState.OFF, FlagState.OFF));
        when(controls.forProject(any(), any())).thenReturn(GenerationControlsView.DEFAULTS);
        when(promptBuilder.buildPrompt(any(), any(), anyString())).thenReturn(new BuiltPrompt("composed", "no blur"));
        when(costs.estimate(anyString(), anyString(), any())).thenReturn(new CostEstimate(new BigDecimal("40"), "INR"));
        when(foley.deriveCues(any(), any())).thenReturn(List.of());
        when(jobs.save(any())).thenAnswer(call -> {
            savedJob.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(jobs.findById(any())).thenAnswer(call -> Optional.ofNullable(savedJob.get()));
        when(jobPersistence.markQueued(any())).thenAnswer(call -> {
            savedJob.get().setStatus(JobStatus.QUEUED);
            return savedJob.get();
        });
    }

    private ShotContextAssemblyService.AssembledShot shot() {
        ShotContext context = mock(ShotContext.class);
        when(context.shotRef()).thenReturn("shot-02-001");
        when(context.referenceFrames()).thenReturn(List.of(
                new ReferenceFrame(ReferenceKind.STORYBOARD, "preprod", "frames/shot-02-001.png")));
        Character ravi = mock(Character.class);
        when(ravi.faceRefBucket()).thenReturn("cast");
        when(ravi.faceRefObjectKey()).thenReturn("faces/ravi.png");
        when(context.characters()).thenReturn(List.of(ravi));
        return new ShotContextAssemblyService.AssembledShot(context, null, null,
                new ShotContextAssemblyService.ShotPromptSources(shotId, null, null, null, null, OffsetDateTime.now()));
    }

    private ShotGenerationOrchestrator.PreparedShot submit(String prompt, ReferenceFrame continuation) {
        return orchestrator.submitReviewedPrompt(new TenantContext(tenant, UUID.randomUUID()), project, shot(),
                new ShotGenerationOrchestrator.ReviewedSubmission("bytedance/seedance-2.0/fast", 6, 24, prompt, continuation));
    }

    @Test
    void theApprovedTextIsStoredAndSentUnchangedWithNoRewriteOrCompression() {
        String exact = "0-1s Ravi kneels.  1-6s he tightens the valve; the drip stops.";

        submit(exact, null);

        ArgumentCaptor<ShotPrompt> saved = ArgumentCaptor.forClass(ShotPrompt.class);
        verify(prompts).save(saved.capture());
        assertThat(saved.getValue().getPromptOriginal()).isEqualTo(exact);
        assertThat(saved.getValue().getCompressionApplied()).isFalse();
        assertThat(saved.getValue().getPromptCompressed()).isNull();
        assertThat(saved.getValue().getShotId()).isEqualTo(shotId);
        assertThat(saved.getValue().getNegativePrompt()).isEqualTo("no blur");
        verifyNoInteractions(compression);
        verify(promptWriter, never()).writePrompt(any(), any(), any(), anyString(), anyInt(), any());
    }

    @Test
    void theJobRunsAtTheChosenSettingsAndIsQueuedThroughTheUsualLifecycle() {
        ShotGenerationOrchestrator.PreparedShot submitted = submit("0-6s he stands.", null);

        assertThat(savedJob.get().getDurationSeconds()).isEqualTo(6);
        assertThat(savedJob.get().getFps()).isEqualTo(24);
        assertThat(submitted.job().getStatus()).isEqualTo(JobStatus.QUEUED);
        verify(costs).estimate("0-6s he stands.", "bytedance/seedance-2.0/fast", 6);
        ArgumentCaptor<VideoGenerationRequestedEvent> event = ArgumentCaptor.forClass(VideoGenerationRequestedEvent.class);
        verify(publisher).publish(event.capture());
        // The creator settled the length in the studio; the render must not resize it afterwards.
        assertThat(event.getValue().fitChoice()).isEqualTo(DialogueFitChoice.KEEP_PLANNED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviousShotsLastFrameLeadsAndEveryOtherReferenceKeepsItsOrder() {
        submit("0-6s he stands.", new ReferenceFrame(ReferenceKind.PRIOR_SHOT_LAST_FRAME, "postprod", "shot-frames/prev/last.jpg"));

        ArgumentCaptor<List<ShotPromptReference>> saved = ArgumentCaptor.forClass(List.class);
        verify(references).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ShotPromptReference::getRefKind).containsExactly(
                ReferenceKind.PRIOR_SHOT_LAST_FRAME, ReferenceKind.STORYBOARD, ReferenceKind.CHARACTER_FACE);
        assertThat(saved.getValue()).extracting(ShotPromptReference::getSlotIndex).containsExactly(0, 1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAContinuationFrameTheShotsOwnFrameStillLeads() {
        submit("0-6s he stands.", null);

        ArgumentCaptor<List<ShotPromptReference>> saved = ArgumentCaptor.forClass(List.class);
        verify(references).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ShotPromptReference::getRefKind)
                .containsExactly(ReferenceKind.STORYBOARD, ReferenceKind.CHARACTER_FACE);
    }

    // ---------------------------------------------------------------- generation controls

    private VideoGenJob existingJob(JobStatus status) {
        VideoGenJob job = VideoGenJob.builder().jobId(UUID.randomUUID()).tenantId(tenant).projectId(project)
                .shotRef("shot-02-001").status(status)
                .approvalStatus(com.dalai.llama.videogen.domain.ApprovalStatus.PENDING).build();
        savedJob.set(job);
        return job;
    }

    private void approve(VideoGenJob job) {
        orchestrator.approve(new TenantContext(tenant, UUID.randomUUID()), job.getJobId(), DialogueFitChoice.KEEP_PLANNED);
    }

    @Test
    void withDuplicateProtectionOnAFinishedOrRenderingShotIsNotQueuedAgain() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approve(existingJob(JobStatus.COMPLETED)))
                .hasMessageContaining("already generated");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approve(existingJob(JobStatus.PROCESSING)))
                .hasMessageContaining("already being generated");
        verify(publisher, never()).publish(any());
    }

    @Test
    void aFailedRenderCanAlwaysBeTriedAgain() {
        approve(existingJob(JobStatus.FAILED));

        verify(publisher).publish(any());
    }

    @Test
    void withDuplicateProtectionOffTheCreatorDecides() {
        when(controls.forProject(any(), any())).thenReturn(new GenerationControlsView(false, true, true, false, false, true, true));

        approve(existingJob(JobStatus.COMPLETED));

        verify(publisher).publish(any());
    }

    @Test
    void withAutoDubOffTheModelPerformsTheLineItself() {
        when(controls.forProject(any(), any())).thenReturn(new GenerationControlsView(false, false, true, true, false, true, true));
        when(dubbing.canAutoDub(any())).thenReturn(true);
        ShotContextAssemblyService.AssembledShot assembled = shot();
        when(assembled.shotContext().dialogueBeats()).thenReturn(List.of(new com.dalai.llama.videogen.dto.shotcontext.DialogueBeat(
                BigDecimal.ZERO, BigDecimal.ONE, "Ho gaya.", "ravi", null, "voice-1", "elevenlabs", null, null, "hi")));

        orchestrator.submitReviewedPrompt(new TenantContext(tenant, UUID.randomUUID()), project, assembled,
                new ShotGenerationOrchestrator.ReviewedSubmission("bytedance/seedance-2.0/fast", 6, 24, "0-6s.", null));

        // Not muted: with auto-dub off there is no cloned voice coming, so the clip carries its own.
        assertThat(savedJob.get().isMuteAudio()).isFalse();
    }
}
