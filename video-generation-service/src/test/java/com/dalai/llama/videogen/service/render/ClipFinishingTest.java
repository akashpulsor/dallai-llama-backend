package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.postproduction.PostProductionClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every clip is rendered silent. One at the shot's length or longer goes straight to post-production
 * to be trimmed and given the recorded take; a shorter one waits for the creator to slow it to the
 * shot after generation. Nothing in here fails a render.
 */
class ClipFinishingTest {

    private final VideoGenJobRepository jobs = mock(VideoGenJobRepository.class);
    private final PostProductionClient postProduction = mock(PostProductionClient.class);
    private final ClipFinishing finishing = new ClipFinishing(jobs, postProduction);

    private final ShotPrompt prompt = ShotPrompt.builder().promptId(UUID.randomUUID()).shotId(UUID.randomUUID()).build();

    private VideoGenJob job(int generated, Integer planned) {
        return VideoGenJob.builder().jobId(UUID.randomUUID()).tenantId(UUID.randomUUID()).projectId(UUID.randomUUID())
                .durationSeconds(generated).plannedDurationSeconds(planned).muteAudio(true).build();
    }

    @Test
    void aClipAtTheShotsLengthWithALineGetsTheRecordedTakeLaidOn() {
        VideoGenJob job = job(7, 7);
        UUID requestId = UUID.randomUUID();
        when(postProduction.requestConform(job.getTenantId(), job.getProjectId(), prompt.getShotId(), 7, true)).thenReturn(requestId);

        assertThat(finishing.requestFinish(job, prompt, true)).isEqualTo(requestId);
        assertThat(job.getConformRequestId()).isEqualTo(requestId);
        verify(jobs).save(job);
    }

    @Test
    void aLongerClipIsTrimmedToTheShotEvenWithNothingToSay() {
        VideoGenJob job = job(8, 7);
        when(postProduction.requestConform(any(), any(), any(), any(Integer.class), any(Boolean.class))).thenReturn(UUID.randomUUID());

        assertThat(finishing.requestFinish(job, prompt, false)).isNotNull();
    }

    @Test
    void aShorterClipIsLeftForTheCreatorToSlowAfterGeneration() {
        assertThat(finishing.requestFinish(job(3, 7), prompt, true)).isNull();
        verifyNoInteractions(postProduction);
    }

    @Test
    void aSilentShotAtItsOwnLengthNeedsNothing() {
        assertThat(finishing.requestFinish(job(7, 7), prompt, false)).isNull();
        assertThat(finishing.requestFinish(job(7, null), prompt, true)).isNull();
        verifyNoInteractions(postProduction);
    }

    @Test
    void aRequestPostProductionRefusesKeepsTheClip() {
        when(postProduction.requestConform(any(), any(), any(), any(Integer.class), any(Boolean.class)))
                .thenThrow(VideoGenException.upstream("down"));

        assertThat(finishing.requestFinish(job(7, 7), prompt, true)).isNull();
    }

    @Test
    void aShorterClipSlowedToTheShotTakesThatShareOfEachShotSecond() {
        // 3s clip, 7s shot: slowed 7/3, so one shot second is 3/7 of a clip second.
        assertThat(ClipFinishing.clipSecondsPerShotSecond(3, new BigDecimal("7.000"))).isEqualByComparingTo("0.428571");
        // Not slowed: played as rendered or trimmed.
        assertThat(ClipFinishing.clipSecondsPerShotSecond(7, new BigDecimal("7.000"))).isEqualByComparingTo("1");
        assertThat(ClipFinishing.clipSecondsPerShotSecond(8, new BigDecimal("7.000"))).isEqualByComparingTo("1");
        assertThat(ClipFinishing.clipSecondsPerShotSecond(3, null)).isEqualByComparingTo("1");
    }
}
