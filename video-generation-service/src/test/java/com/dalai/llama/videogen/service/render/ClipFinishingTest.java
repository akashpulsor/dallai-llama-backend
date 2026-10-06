package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.BeatDubbingService;
import com.dalai.llama.videogen.service.DispatchResult;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.postproduction.PostProductionClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Generate short, then let post-production bring the clip to its planned length. A clip that will be
 * conformed is generated silent and finished there; every other clip is finished here as before.
 * Nothing in here fails a render.
 */
class ClipFinishingTest {

    private final BeatDubbingService dubbing = mock(BeatDubbingService.class);
    private final VideoGenJobRepository jobs = mock(VideoGenJobRepository.class);
    private final PostProductionClient postProduction = mock(PostProductionClient.class);
    private final ClipFinishing finishing = new ClipFinishing(dubbing, jobs, postProduction);

    private static final List<DialogueBeat> BEATS = List.of(new DialogueBeat(BigDecimal.ZERO, BigDecimal.ONE,
            "Ho gaya.", "ravi", null, "voice-1", "elevenlabs", null, null, "hi"));
    private final ShotPrompt prompt = ShotPrompt.builder().promptId(UUID.randomUUID()).shotId(UUID.randomUUID()).build();

    private VideoGenJob job(int generated, Integer planned) {
        return VideoGenJob.builder().jobId(UUID.randomUUID()).tenantId(UUID.randomUUID()).projectId(UUID.randomUUID())
                .durationSeconds(generated).plannedDurationSeconds(planned).muteAudio(true).build();
    }

    private static DispatchResult rendered() {
        DispatchResult result = mock(DispatchResult.class);
        when(result.outputUri()).thenReturn("https://fal/clip.mp4");
        when(result.actualCost()).thenReturn(new BigDecimal("0.20"));
        return result;
    }

    @Test
    void aClipGeneratedShorterThanPlannedIsLeftForPostProductionToFinish() {
        when(dubbing.canAutoDub(any())).thenReturn(true);

        ClipFinishing.Finished finished = finishing.finish(job(3, 6), prompt, BEATS, rendered(), GenerationControlsView.DEFAULTS);

        assertThat(finished.outputUri()).isEqualTo("https://fal/clip.mp4");
        // Dubbing a 3s clip would squeeze the line; conform lays it on at the full 6s.
        verify(dubbing, never()).dub(anyString(), any(), any(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aClipAtItsPlannedLengthIsDubbedHereAndCarriesNoMusic() {
        when(dubbing.canAutoDub(any())).thenReturn(true);
        when(dubbing.dub(anyString(), any(), any(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new BeatDubbingService.DubResult("https://dubbed.mp4", new BigDecimal("0.05")));
        VideoGenJob job = job(6, 6);

        ClipFinishing.Finished finished = finishing.finish(job, prompt, BEATS, rendered(), GenerationControlsView.DEFAULTS);

        // Music is a sound layer mixed into the film, never laid on the clip.
        assertThat(finished.outputUri()).isEqualTo("https://dubbed.mp4");
        assertThat(finished.cost()).isEqualByComparingTo("0.25");
        assertThat(job.getDubSucceeded()).isTrue();
    }

    @Test
    void aFailedDubKeepsTheSilentClipInsteadOfFailingTheRender() {
        when(dubbing.canAutoDub(any())).thenReturn(true);
        when(dubbing.dub(anyString(), any(), any(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("ElevenLabs down"));
        VideoGenJob job = job(6, 6);

        ClipFinishing.Finished finished = finishing.finish(job, prompt, BEATS, rendered(), GenerationControlsView.DEFAULTS);

        assertThat(finished.outputUri()).isEqualTo("https://fal/clip.mp4");
        assertThat(job.getDubSucceeded()).isFalse();
    }

    @Test
    void aShortClipAsksPostProductionToSlowItToThePlannedLengthWithInterpolation() {
        UUID requestId = UUID.randomUUID();
        VideoGenJob job = job(3, 6);
        when(postProduction.requestConform(job.getTenantId(), job.getProjectId(), prompt.getShotId(), 6, true)).thenReturn(requestId);

        assertThat(finishing.requestConform(job, prompt, GenerationControlsView.DEFAULTS)).isEqualTo(requestId);
        assertThat(job.getConformRequestId()).isEqualTo(requestId);
    }

    @Test
    void conformingCanBeSwitchedOffAndAFailedRequestNeverFailsTheRender() {
        VideoGenJob job = job(3, 6);
        GenerationControlsView off = new GenerationControlsView(false, true, true, true, false, false, true);
        assertThat(finishing.requestConform(job, prompt, off)).isNull();
        verifyNoInteractions(postProduction);

        when(postProduction.requestConform(any(), any(), any(), any(Integer.class), any(Boolean.class)))
                .thenThrow(VideoGenException.upstream("post-production down"));
        assertThat(finishing.requestConform(job, prompt, GenerationControlsView.DEFAULTS)).isNull();
    }

    @Test
    void aShortClipThatWillBeConformedIsGeneratedSilent() {
        ShotContext shot = mock(ShotContext.class);
        when(shot.narrative()).thenReturn(new Narrative("He speaks.", null, null, "Ho gaya."));
        GenerationControlsView noDub = new GenerationControlsView(false, false, true, true, false, true, true);

        // Slowed-down speech is unusable, so the model is told to make none.
        assertThat(finishing.muteAudio(shot, 3, 6, noDub)).isTrue();
        // At its planned length with no dub coming, the model performs the line itself.
        assertThat(finishing.muteAudio(shot, 6, 6, noDub)).isFalse();
    }
}
