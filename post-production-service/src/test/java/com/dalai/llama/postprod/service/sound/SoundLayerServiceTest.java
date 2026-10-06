package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.dto.GenerateSoundLayerRequest;
import com.dalai.llama.postprod.dto.SoundLayerView;
import com.dalai.llama.postprod.dto.UpdateSoundLayerRequest;
import com.dalai.llama.postprod.kafka.SoundLayerRequestedEvent;
import com.dalai.llama.postprod.kafka.SoundLayerRequestedPublisher;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.PostProductionException;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The request side: a sound is saved QUEUED and handed to the worker; no ffmpeg runs here. */
class SoundLayerServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final SoundLayerRepository repository = mock(SoundLayerRepository.class);
    private final SoundLayerRequestedPublisher publisher = mock(SoundLayerRequestedPublisher.class);
    private final ClipObjectStore objectStore = mock(ClipObjectStore.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final PreProductionClient preProduction = mock(PreProductionClient.class);
    private final SoundLayerService service = new SoundLayerService(repository, publisher, objectStore, ffmpeg, preProduction);

    @BeforeEach
    void setUp() throws Exception {
        when(preProduction.listShots(tenantId, projectId)).thenReturn(List.of(new PreProductionShotSummary(shotId, "S12", 12, 6, false)));
        when(ffmpeg.createWorkDir(any())).thenReturn(Files.createTempDirectory("sound-layer-test"));
        when(objectStore.bucket()).thenReturn("post-production");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aSoundEffectIsQueuedWithItsPlacementAndNothingIsGeneratedOnTheRequest() {
        SoundLayerView bell = service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(shotId, SoundLayerKind.SOUND_EFFECT, "a single temple bell, long ring", null, 1500));

        assertThat(bell.status()).isEqualTo(FrameExtractionStatus.QUEUED);
        assertThat(bell.source()).isEqualTo(SoundLayerSource.GENERATED);
        assertThat(bell.audioUrl()).isNull();
        assertThat(bell.offsetMs()).isEqualTo(1500);
        assertThat(bell.volumeDb()).isEqualByComparingTo("-4");
        assertThat(bell.fadeInMs()).isZero();
        assertThat(bell.included()).isTrue();
        verify(publisher).publish(new SoundLayerRequestedEvent(bell.layerId(), tenantId, projectId, userId));
        verify(ffmpeg, never()).probe(any());
    }

    @Test
    void musicKeepsItsRequestedLengthForTheWorkerAndSitsLowerWithFades() {
        service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(shotId, SoundLayerKind.MUSIC, "soft tanpura drone", 12, null));

        ArgumentCaptor<SoundLayer> saved = ArgumentCaptor.forClass(SoundLayer.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRequestedSeconds()).isEqualTo(12);
        assertThat(saved.getValue().getVolumeDb()).isEqualByComparingTo("-14");
        assertThat(saved.getValue().getFadeInMs()).isEqualTo(500);
        assertThat(saved.getValue().getFadeOutMs()).isEqualTo(1500);
        assertThat(saved.getValue().getOffsetMs()).isZero();
    }

    @Test
    void aShotFromAnotherProjectIsRefusedBeforeAnythingIsQueued() {
        assertThatThrownBy(() -> service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(UUID.randomUUID(), SoundLayerKind.SOUND_EFFECT, "bell", null, 0)))
                .isInstanceOf(PostProductionException.class);
        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void anUploadIsStoredAsItArrivesAndQueuedForChecking() {
        SoundLayerView upload = service.upload(tenantId, projectId, userId, shotId, SoundLayerKind.SOUND_EFFECT, 200,
                new MockMultipartFile("file", "Bell.WAV", "audio/wav", new byte[2048]));

        assertThat(upload.status()).isEqualTo(FrameExtractionStatus.QUEUED);
        verify(objectStore).upload(eq("sound-layers/" + projectId + "/" + upload.layerId() + ".wav"), any(), eq("audio/wav"));
        verify(publisher).publish(any());
        verify(ffmpeg, never()).probe(any());
    }

    @Test
    void aSoundThatCannotBeQueuedIsMarkedFailedNotLeftWaiting() {
        doThrow(PostProductionException.upstream("kafka down", new RuntimeException())).when(publisher).publish(any());

        assertThatThrownBy(() -> service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(shotId, SoundLayerKind.SOUND_EFFECT, "bell", null, 0)))
                .isInstanceOf(PostProductionException.class);

        ArgumentCaptor<SoundLayer> saved = ArgumentCaptor.forClass(SoundLayer.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
    }

    @Test
    void movingLevellingAndSwitchingOffChangeOnlyWhatIsSent() {
        SoundLayer layer = SoundLayer.builder().layerId(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                .shotId(shotId).kind(SoundLayerKind.SOUND_EFFECT).source(SoundLayerSource.GENERATED)
                .status(FrameExtractionStatus.COMPLETED).bucket("post-production").objectKey("sound-layers/x.wav")
                .offsetMs(0).volumeDb(new BigDecimal("-4")).fadeInMs(0).fadeOutMs(150).included(true).build();
        when(repository.findByLayerIdAndTenantIdAndProjectId(layer.getLayerId(), tenantId, projectId)).thenReturn(Optional.of(layer));

        SoundLayerView moved = service.update(tenantId, projectId, layer.getLayerId(),
                new UpdateSoundLayerRequest(2200, null, null, null, false));

        assertThat(moved.offsetMs()).isEqualTo(2200);
        assertThat(moved.included()).isFalse();
        assertThat(moved.volumeDb()).isEqualByComparingTo("-4");
        assertThat(moved.fadeOutMs()).isEqualTo(150);
    }

    @Test
    void onlyPreparedLayersReachTheFilm() {
        service.includedForFilm(projectId);

        verify(repository).findByProjectIdAndIncludedTrueAndStatus(projectId, FrameExtractionStatus.COMPLETED);
    }

    @Test
    void theExtensionComesFromTheUrlPathNotItsQuery() {
        assertThat(SoundLayerService.extensionOf("https://fal.media/files/bell.WAV?token=a.b")).isEqualTo(".wav");
        assertThat(SoundLayerService.extensionOf("https://fal.media/files/bell")).isEqualTo(".mp3");
        assertThat(SoundLayerService.extensionOf("my bell.exe")).isEqualTo(".mp3");
        assertThat(SoundLayerService.extensionOf(null)).isEqualTo(".mp3");
    }
}
