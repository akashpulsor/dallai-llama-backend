package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipConform;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.kafka.ShotConformRequestedPublisher;
import com.dalai.llama.postprod.repository.ShotClipConformRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conforming runs on the consumer, from the shot's newest generated clip, and leaves the shot playing
 * at its planned length -- or records why not, without failing anything else.
 */
class ShotConformServiceTest {

    private final ShotClipConformRepository conforms = mock(ShotClipConformRepository.class);
    private final ShotClipVersionService clipVersions = mock(ShotClipVersionService.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ShotConformRequestedPublisher publisher = mock(ShotConformRequestedPublisher.class);
    private final AiSlowDown aiSlowDown = mock(AiSlowDown.class);
    private final ShotConformService service = new ShotConformService(conforms, clipVersions, ffmpeg, publisher, aiSlowDown);

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID shot = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();

    @TempDir
    Path workDir;

    @BeforeEach
    void setUp() {
        when(conforms.save(any())).thenAnswer(call -> call.getArgument(0));
        when(ffmpeg.createWorkDir(anyString())).thenReturn(workDir);
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(true, false, new BigDecimal("3.0"), 720, 1280));
        when(ffmpeg.frameRate(any())).thenReturn(30.0);
    }

    private ShotClipConform queued() {
        ShotClipConform row = ShotClipConform.builder().requestId(UUID.randomUUID()).tenantId(tenant).projectId(project)
                .shotId(shot).targetSeconds(new BigDecimal("6.000")).interpolate(true).activate(true).ai(false)
                .status(FrameExtractionStatus.QUEUED).createdAt(OffsetDateTime.now()).build();
        when(conforms.findById(row.getRequestId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void askingQueuesTheWorkAndInterpolatesUnlessToldNotTo() {
        ShotConformDtos.View view = service.request(tenant, project, shot, new ShotConformDtos.Request(new BigDecimal("6"), null, null), true);

        assertThat(view.status()).isEqualTo("QUEUED");
        assertThat(view.interpolate()).isTrue();
        verify(publisher).publish(any());
        verify(ffmpeg, never()).conform(any(), any(), any(), any(Double.class), any(Boolean.class), any(Double.class), any());
    }

    @Test
    void aShortClipIsSlowedToThePlannedLengthWithItsLineAndBecomesTheShotsCut() {
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 3.0,
                "https://minio/take.mp3", 5.4, "Ho gaya.", "GENERATED",
                List.of(new ShotClipSource.PlacedTake("https://minio/take.mp3", 1.2))));
        Path takes = workDir.resolve("takes.wav");
        when(clipVersions.takeTrack(any(), any())).thenReturn(takes);
        ShotClipVersion cut = ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(3).build();
        when(clipVersions.storeConformed(any(), eq(job), any(), any(), any(), any(), eq(true))).thenReturn(cut);

        service.process(row.getRequestId());

        verify(clipVersions).fetch(eq("https://minio/generated.mp4"), any());
        // The take track (each line at its planned start) is what goes under the slowed picture.
        verify(clipVersions).takeTrack(any(), eq(workDir));
        verify(ffmpeg).conform(any(), eq(takes), isNull(), eq(6.0), eq(true), eq(30.0), any());
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
        assertThat(row.getVersionId()).isEqualTo(cut.getVersionId());
        assertThat(row.getSourceJobId()).isEqualTo(job);
    }

    @Test
    void aConformNeverLaysMusicOnTheClip() {
        // Shot music is a sound layer mixed into the film when it renders, not part of a cut.
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 3.0, null, null, null, "GENERATED", null));
        when(clipVersions.storeConformed(any(), any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(2).build());

        service.process(row.getRequestId());

        verify(ffmpeg).conform(any(), isNull(), isNull(), eq(6.0), eq(true), eq(30.0), any());
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
    }

    @Test
    void aSlowDownTheCreatorAsksForIsAVersionToChoose_notAnImmediateSwap() {
        ShotConformDtos.View view = service.request(tenant, project, shot, new ShotConformDtos.Request(new BigDecimal("7"), null, null), false);
        assertThat(view.interpolate()).isTrue();

        ShotClipConform row = queued();
        row.setActivate(false);
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 3.0, null, null, null, "GENERATED", null));
        when(clipVersions.storeConformed(any(), any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(4).build());

        service.process(row.getRequestId());

        verify(clipVersions).storeConformed(any(), eq(job), any(), any(), any(), any(), eq(false));
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
    }

    @Test
    void anAiSlowDownHasTopazGenerateTheMissingFrames_thenFfmpegOnlyLaysTheTakeOn() {
        ShotClipConform row = queued();
        row.setTargetSeconds(new BigDecimal("7.000"));
        row.setAi(true);
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(true, false, new BigDecimal("4.0"), 480, 854));
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 4.0, null, null, null, "GENERATED", null));
        Path fitted = workDir.resolve("fitted.mp4");
        when(aiSlowDown.slow(eq(tenant), eq(project), eq(row.getRequestId()), any(), eq(4.0), eq(7.0), eq(30.0), any())).thenReturn(fitted);
        when(clipVersions.storeConformed(any(), any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(2).build());

        service.process(row.getRequestId());

        // The model's frames go in; ffmpeg is told not to interpolate again.
        verify(ffmpeg).conform(eq(fitted), isNull(), isNull(), eq(7.0), eq(false), eq(30.0), any());
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
    }

    @Test
    void theSlowDownFactorIsTheSmallestWholeOneThatReachesTheShot() {
        assertThat(AiSlowDown.factor(4.0, 7.0)).isEqualTo(2);
        assertThat(AiSlowDown.factor(3.0, 7.0)).isEqualTo(3);
        assertThat(AiSlowDown.factor(4.0, 4.4)).isEqualTo(2);
        assertThat(AiSlowDown.factor(1.0, 20.0)).isEqualTo(8);
    }

    @Test
    void aShotWithNoClipFailsTheRequestWithItsReason() {
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(null, null, null, null, null, null, null, null));

        service.process(row.getRequestId());

        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
        assertThat(row.getError()).contains("no generated clip");
    }

    @Test
    void aRedeliveredConformThatAlreadyFinishedIsNotRunAgain() {
        ShotClipConform row = queued();
        row.setStatus(FrameExtractionStatus.COMPLETED);

        service.process(row.getRequestId());

        verify(clipVersions, never()).clipSource(any());
    }
}
