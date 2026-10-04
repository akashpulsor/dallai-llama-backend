package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.domain.entity.ShotFrame;
import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
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
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Frames are taken from the cut the film actually uses, so the next shot continues from what the
 * audience sees -- and taking the same frame twice costs one extraction.
 */
class ShotFrameServiceTest {

    private final ShotClipVersionService clipVersions = mock(ShotClipVersionService.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ClipObjectStore store = mock(ClipObjectStore.class);
    private final ShotFrameRepository frames = mock(ShotFrameRepository.class);
    private final ShotFrameService service = new ShotFrameService(clipVersions, ffmpeg, store, frames,
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
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLastFrameIsTakenFromTheActiveCutAndStoredUnderThatCut() {
        when(clipVersions.active(shot)).thenReturn(Optional.of(activeCut));

        ShotFrameExtractionResult result = service.extract(tenant, project, shot, FrameExtractionMode.LAST_FRAME, null);

        verify(store).download(eq("postprod"), eq("shot-clip-versions/x/v3.mp4"), any());
        verify(ffmpeg).extractFrame(any(), eq(true), any());
        String key = "shot-frames/%s/%s/last_frame/last.jpg".formatted(shot, activeCut.getVersionId());
        verify(store).upload(eq(key), any(), eq("image/jpeg"));

        ArgumentCaptor<List<ShotFrame>> saved = ArgumentCaptor.forClass(List.class);
        verify(frames).saveAll(saved.capture());
        // 4.767s at 30fps is 143 frames; the last is index 142, which starts at 4.733s.
        assertThat(saved.getValue()).singleElement().satisfies(frame -> {
            assertThat(frame.getFrameNumber()).isEqualTo(142L);
            assertThat(frame.getTimestampMs()).isEqualTo(4733L);
            assertThat(frame.getClipVersionId()).isEqualTo(activeCut.getVersionId());
        });
        assertThat(result.frameCount()).isEqualTo(1);
        assertThat(result.clipVersionId()).isEqualTo(activeCut.getVersionId());
    }

    @Test
    void askingAgainForTheSameCutReusesTheStoredFrameWithoutRunningFfmpeg() {
        when(clipVersions.active(shot)).thenReturn(Optional.of(activeCut));
        when(frames.findByTenantIdAndShotIdAndClipVersionIdAndModeOrderByTimestampMsAsc(
                tenant, shot, activeCut.getVersionId(), FrameExtractionMode.LAST_FRAME))
                .thenReturn(List.of(ShotFrame.builder().frameId(UUID.randomUUID()).shotId(shot)
                        .clipVersionId(activeCut.getVersionId()).mode(FrameExtractionMode.LAST_FRAME)
                        .frameNumber(142L).timestampMs(4733L).bucket("postprod").objectKey("k").createdAt(OffsetDateTime.now()).build()));

        ShotFrameExtractionResult result = service.extract(tenant, project, shot, FrameExtractionMode.LAST_FRAME, null);

        assertThat(result.frames()).singleElement().satisfies(frame -> assertThat(frame.objectKey()).isEqualTo("k"));
        verify(ffmpeg, never()).extractFrame(any(), any(Boolean.class), any());
        verify(store, never()).download(any(), any(), any());
    }

    @Test
    void aShotNeverCutHasItsGeneratedClipImportedFirst() {
        when(clipVersions.active(shot)).thenReturn(Optional.empty());
        when(clipVersions.importGeneratedBaseline(any())).thenReturn(activeCut);

        service.extract(tenant, project, shot, FrameExtractionMode.FIRST_FRAME, null);

        verify(clipVersions).importGeneratedBaseline(new ShotClipVersionService.Context(tenant, project, shot, null, null));
        verify(ffmpeg).extractFrame(any(), eq(false), any());
    }

    @Test
    void aShotWithNoVideoIsAConflictTheCreatorCanActOn() {
        when(clipVersions.active(shot)).thenReturn(Optional.empty());
        when(clipVersions.importGeneratedBaseline(any())).thenThrow(new ClipProcessingException("This shot has not been generated yet"));

        assertThatThrownBy(() -> service.extract(tenant, project, shot, FrameExtractionMode.LAST_FRAME, null))
                .isInstanceOf(PostProductionException.class)
                .satisfies(ex -> assertThat(((PostProductionException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aSampleNeedsARate() {
        assertThatThrownBy(() -> service.extract(tenant, project, shot, FrameExtractionMode.SAMPLE, null))
                .isInstanceOf(PostProductionException.class)
                .hasMessageContaining("sampleFps");
    }

    @Test
    void anUnknownFrameRateStillTimesTheLastFrameAtTheEndOfTheClip() {
        assertThat(ShotFrameService.lastPosition(0, 4.767)).containsExactly(-1, 4767);
        assertThat(ShotFrameService.lastPosition(24, 5.0)).containsExactly(119, 4958);
    }
}
