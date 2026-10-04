package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipConform;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.kafka.ShotConformRequestedPublisher;
import com.dalai.llama.postprod.repository.ShotClipConformRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
    private final PreProductionClient preProduction = mock(PreProductionClient.class);
    private final ShotConformRequestedPublisher publisher = mock(ShotConformRequestedPublisher.class);
    private final ShotConformService service = new ShotConformService(conforms, clipVersions, ffmpeg, preProduction, publisher);

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
                .shotId(shot).targetSeconds(new BigDecimal("6.000")).interpolate(true)
                .status(FrameExtractionStatus.QUEUED).createdAt(OffsetDateTime.now()).build();
        when(conforms.findById(row.getRequestId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void askingQueuesTheWorkAndInterpolatesUnlessToldNotTo() {
        ShotConformDtos.View view = service.request(tenant, project, shot, new ShotConformDtos.Request(new BigDecimal("6"), null));

        assertThat(view.status()).isEqualTo("QUEUED");
        assertThat(view.interpolate()).isTrue();
        verify(publisher).publish(any());
        verify(ffmpeg, never()).conform(any(), any(), any(), any(Double.class), any(Boolean.class), any(Double.class), any());
    }

    @Test
    void aShortClipIsSlowedToThePlannedLengthWithItsLineAndBecomesTheShotsCut() {
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 3.0,
                "https://minio/take.mp3", 5.4, "Ho gaya.", "GENERATED"));
        when(preProduction.getBackgroundMusicUrl(tenant, shot)).thenReturn(null);
        ShotClipVersion cut = ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(3).build();
        when(clipVersions.storeConformed(any(), eq(job), any(), any(), any(), any())).thenReturn(cut);

        service.process(row.getRequestId());

        verify(clipVersions).fetch(eq("https://minio/generated.mp4"), any());
        verify(clipVersions).fetch(eq("https://minio/take.mp3"), any());
        verify(ffmpeg).conform(any(), any(), isNull(), eq(6.0), eq(true), eq(30.0), any());
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
        assertThat(row.getVersionId()).isEqualTo(cut.getVersionId());
        assertThat(row.getSourceJobId()).isEqualTo(job);
    }

    @Test
    void musicThatCannotBeFetchedIsLeftOffRatherThanFailingTheConform() {
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(job, "https://minio/generated.mp4", 3.0, null, null, null, "GENERATED"));
        when(preProduction.getBackgroundMusicUrl(tenant, shot)).thenThrow(new RuntimeException("pre-production down"));
        when(clipVersions.storeConformed(any(), any(), any(), any(), any(), any()))
                .thenReturn(ShotClipVersion.builder().versionId(UUID.randomUUID()).versionNumber(2).build());

        service.process(row.getRequestId());

        verify(ffmpeg).conform(any(), isNull(), isNull(), eq(6.0), eq(true), eq(30.0), any());
        assertThat(row.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
    }

    @Test
    void aShotWithNoClipFailsTheRequestWithItsReason() {
        ShotClipConform row = queued();
        when(clipVersions.clipSource(any())).thenReturn(new ShotClipSource(null, null, null, null, null, null, null));

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
