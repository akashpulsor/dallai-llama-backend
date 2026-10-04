package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.domain.entity.ShotFrame;
import com.dalai.llama.postprod.domain.entity.ShotFrameExtraction;
import com.dalai.llama.postprod.dto.FrameExtractionRequestedEvent;
import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
import com.dalai.llama.postprod.kafka.FrameExtractionRequestedPublisher;
import com.dalai.llama.postprod.repository.ShotFrameExtractionRepository;
import com.dalai.llama.postprod.repository.ShotFrameRepository;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.ClipProcessingException;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Frame extraction never runs on a request thread: asking queues it, the consumer works it one at a
 * time, the caller polls. Frames come from the cut the film actually uses, and a frame already taken
 * from that cut is answered without queueing anything.
 */
class ShotFrameServiceTest {

    private final ShotClipVersionService clipVersions = mock(ShotClipVersionService.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ClipObjectStore store = mock(ClipObjectStore.class);
    private final ShotFrameRepository frames = mock(ShotFrameRepository.class);
    private final ShotFrameExtractionRepository requests = mock(ShotFrameExtractionRepository.class);
    private final FrameExtractionRequestedPublisher publisher = mock(FrameExtractionRequestedPublisher.class);
    private final ShotFrameService service = new ShotFrameService(clipVersions, ffmpeg, store, frames, requests, publisher,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID shot = UUID.randomUUID();
    private final ShotClipVersion activeCut = ShotClipVersion.builder()
            .versionId(UUID.randomUUID()).tenantId(tenant).bucket("postprod").objectKey("shot-clip-versions/x/v3.mp4")
            .durationSeconds(new BigDecimal("4.767")).build();

    @TempDir
    Path workDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxFrames", 900);
        when(ffmpeg.createWorkDir(anyString())).thenReturn(workDir);
        when(ffmpeg.frameRate(any())).thenReturn(30.0);
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(true, true, new BigDecimal("4.767"), 720, 1280));
        when(store.bucket()).thenReturn("postprod");
        when(store.frameKeyFor(any(), any(), anyString(), anyString()))
                .thenAnswer(call -> "shot-frames/%s/%s/%s/%s".formatted(call.getArgument(0), call.getArgument(1),
                        call.<String>getArgument(2).toLowerCase(), call.getArgument(3)));
        when(frames.findByTenantIdAndShotIdAndClipVersionIdAndModeOrderByTimestampMsAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private ShotFrameExtraction queued(FrameExtractionMode mode) {
        ShotFrameExtraction row = ShotFrameExtraction.builder().requestId(UUID.randomUUID()).tenantId(tenant)
                .projectId(project).shotId(shot).mode(mode).status(FrameExtractionStatus.QUEUED)
                .createdAt(OffsetDateTime.now()).build();
        when(requests.findById(row.getRequestId())).thenReturn(Optional.of(row));
        return row;
    }

    // ---------------------------------------------------------------- request

    @Test
    void askingQueuesTheWorkAndRunsNoFfmpegOnTheRequestThread() {
        when(clipVersions.active(shot)).thenReturn(Optional.of(activeCut));

        ShotFrameExtractionResult result = service.request(tenant, project, shot, FrameExtractionMode.LAST_FRAME, null);

        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.requestId()).isNotNull();
        ArgumentCaptor<FrameExtractionRequestedEvent> event = ArgumentCaptor.forClass(FrameExtractionRequestedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo(result.requestId());
        verify(store, never()).download(any(), any(), any());
        verify(ffmpeg, never()).extractFrame(any(), any(Boolean.class), any());
    }

    @Test
    void aLastFrameAlreadyTakenFromTheCurrentCutIsAnsweredWithoutQueueing() {
        when(clipVersions.active(shot)).thenReturn(Optional.of(activeCut));
        when(frames.findByTenantIdAndShotIdAndClipVersionIdAndModeOrderByTimestampMsAsc(
                tenant, shot, activeCut.getVersionId(), FrameExtractionMode.LAST_FRAME))
                .thenReturn(List.of(ShotFrame.builder().frameId(UUID.randomUUID()).shotId(shot)
                        .clipVersionId(activeCut.getVersionId()).mode(FrameExtractionMode.LAST_FRAME)
                        .frameNumber(142L).timestampMs(4733L).bucket("postprod").objectKey("k").createdAt(OffsetDateTime.now()).build()));

        ShotFrameExtractionResult result = service.request(tenant, project, shot, FrameExtractionMode.LAST_FRAME, null);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.requestId()).isNull();
        assertThat(result.frames()).singleElement().satisfies(frame -> assertThat(frame.objectKey()).isEqualTo("k"));
        verify(publisher, never()).publish(any());
    }

    @Test
    void aRequestThatCannotBeQueuedIsRecordedAsFailed() {
        doThrow(PostProductionException.upstream("kafka down")).when(publisher).publish(any());

        assertThatThrownBy(() -> service.request(tenant, project, shot, FrameExtractionMode.ALL, null))
                .isInstanceOf(PostProductionException.class);
        ArgumentCaptor<ShotFrameExtraction> saved = ArgumentCaptor.forClass(ShotFrameExtraction.class);
        verify(requests, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
    }

    @Test
    void aSampleNeedsARate() {
        assertThatThrownBy(() -> service.request(tenant, project, shot, FrameExtractionMode.SAMPLE, null))
                .isInstanceOf(PostProductionException.class)
                .hasMessageContaining("sampleFps");
    }

    // ---------------------------------------------------------------- process (the consumer)

    @Test
    @SuppressWarnings("unchecked")
    void theConsumerTakesTheLastFrameOfTheActiveCutAndStoresItUnderThatCut() {
        when(clipVersions.active(shot)).thenReturn(Optional.of(activeCut));
        ShotFrameExtraction row = queued(FrameExtractionMode.LAST_FRAME);

        service.process(row.getRequestId());

        verify(store).download(eq("postprod"), eq("shot-clip-versions/x/v3.mp4"), any());
        verify(ffmpeg).extractFrame(any(), eq(true), any());
        verify(store).upload(eq("shot-frames/%s/%s/last_frame/last.jpg".formatted(shot, activeCut.getVersionId())),
                any(), eq("image/jpeg"));
        ArgumentCaptor<List<ShotFrame>> saved = ArgumentCaptor.forClass(List.class);
        verify(frames).saveAll(saved.capture());
        // 4.767s at 30fps is 143 frames; the last is index 142, which starts at 4.733s.
        assertThat(saved.getValue()).singleElement().satisfies(frame -> {
            assertThat(frame.getFrameNumber()).isEqualTo(142L);
            assertThat(frame.getTimestampMs()).isEqualTo(4733L);
        });
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
        assertThat(row.getClipVersionId()).isEqualTo(activeCut.getVersionId());
    }

    @Test
    void aShotNeverCutHasItsGeneratedClipImportedFirst() {
        when(clipVersions.active(shot)).thenReturn(Optional.empty());
        when(clipVersions.importGeneratedBaseline(any())).thenReturn(activeCut);
        ShotFrameExtraction row = queued(FrameExtractionMode.FIRST_FRAME);

        service.process(row.getRequestId());

        verify(clipVersions).importGeneratedBaseline(new ShotClipVersionService.Context(tenant, project, shot, null, null));
        verify(ffmpeg).extractFrame(any(), eq(false), any());
    }

    @Test
    void aShotWithNoVideoFailsTheRequestWithAReasonInsteadOfThrowingIntoKafka() {
        when(clipVersions.active(shot)).thenReturn(Optional.empty());
        when(clipVersions.importGeneratedBaseline(any())).thenThrow(new ClipProcessingException("This shot has not been generated yet"));
        ShotFrameExtraction row = queued(FrameExtractionMode.LAST_FRAME);

        service.process(row.getRequestId());

        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
        assertThat(row.getError()).contains("no video yet");
    }

    @Test
    void aRedeliveredRequestThatAlreadyFinishedIsNotWorkedAgain() {
        ShotFrameExtraction row = queued(FrameExtractionMode.LAST_FRAME);
        row.setStatus(FrameExtractionStatus.COMPLETED);

        service.process(row.getRequestId());

        verify(store, never()).download(any(), any(), any());
    }

    @Test
    void anUnknownFrameRateStillTimesTheLastFrameAtTheEndOfTheClip() {
        assertThat(ShotFrameService.lastPosition(0, 4.767)).containsExactly(-1, 4767);
        assertThat(ShotFrameService.lastPosition(24, 5.0)).containsExactly(119, 4958);
    }
}
