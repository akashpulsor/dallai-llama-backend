package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.dto.GenerateSoundLayerRequest;
import com.dalai.llama.postprod.dto.SoundLayerView;
import com.dalai.llama.postprod.dto.UpdateSoundLayerRequest;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.AudioGenerationResult;
import com.dalai.llama.postprod.service.FoleyGenerationService;
import com.dalai.llama.postprod.service.MusicGenerationService;
import com.dalai.llama.postprod.service.PostProductionException;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SoundLayerServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final SoundLayerRepository repository = mock(SoundLayerRepository.class);
    private final FoleyGenerationService foley = mock(FoleyGenerationService.class);
    private final MusicGenerationService music = mock(MusicGenerationService.class);
    private final ShotClipVersionService clips = mock(ShotClipVersionService.class);
    private final ClipObjectStore objectStore = mock(ClipObjectStore.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final PreProductionClient preProduction = mock(PreProductionClient.class);
    private final SoundLayerService service =
            new SoundLayerService(repository, foley, music, clips, objectStore, ffmpeg, preProduction);

    @BeforeEach
    void setUp() throws Exception {
        when(preProduction.listShots(tenantId, projectId)).thenReturn(List.of(new PreProductionShotSummary(shotId, "S12", 12, 6, false)));
        when(ffmpeg.createWorkDir(any())).thenReturn(Files.createTempDirectory("sound-layer-test"));
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(false, true, new BigDecimal("3.2"), null, null));
        when(objectStore.bucket()).thenReturn("post-production");
        when(objectStore.presignedUrl(any(), any())).thenReturn("https://minio/signed");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aSoundEffectIsGeneratedFromItsDescriptionStoredAndPlacedOnTheShot() {
        when(foley.generateFoley(eq(tenantId), eq(projectId), any(), isNull(), eq("a single temple bell, long ring"), isNull()))
                .thenReturn(new AudioGenerationResult(UUID.randomUUID(), "https://fal.media/bell.wav?sig=1", BigDecimal.ONE));

        SoundLayerView bell = service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(shotId, SoundLayerKind.SOUND_EFFECT, "a single temple bell, long ring", null, 1500));

        assertThat(bell.shotId()).isEqualTo(shotId);
        assertThat(bell.kind()).isEqualTo(SoundLayerKind.SOUND_EFFECT);
        assertThat(bell.source()).isEqualTo(SoundLayerSource.GENERATED);
        assertThat(bell.offsetMs()).isEqualTo(1500);
        assertThat(bell.volumeDb()).isEqualByComparingTo("-4");
        assertThat(bell.fadeInMs()).isZero();
        assertThat(bell.durationSeconds()).isEqualByComparingTo("3.2");
        assertThat(bell.included()).isTrue();
        verify(clips).fetch(eq("https://fal.media/bell.wav?sig=1"), any());
        verify(objectStore).upload(eq("sound-layers/" + projectId + "/" + bell.layerId() + ".wav"), any(), eq("audio/wav"));
        verifyNoInteractions(music);
    }

    @Test
    void musicIsGeneratedAsMusicAndSitsLowerWithFades() {
        when(music.generateMusic(eq(tenantId), eq(projectId), any(), eq("soft tanpura drone"), eq(12), isNull()))
                .thenReturn(new AudioGenerationResult(UUID.randomUUID(), "https://elevenlabs/cue.mp3", BigDecimal.ONE));

        SoundLayerView cue = service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(shotId, SoundLayerKind.MUSIC, "soft tanpura drone", 12, null));

        assertThat(cue.volumeDb()).isEqualByComparingTo("-14");
        assertThat(cue.fadeInMs()).isEqualTo(500);
        assertThat(cue.fadeOutMs()).isEqualTo(1500);
        assertThat(cue.offsetMs()).isZero();
        verifyNoInteractions(foley);
    }

    @Test
    void aShotFromAnotherProjectIsRefusedBeforeAnythingIsPaidFor() {
        assertThatThrownBy(() -> service.generate(tenantId, projectId, userId,
                new GenerateSoundLayerRequest(UUID.randomUUID(), SoundLayerKind.SOUND_EFFECT, "bell", null, 0)))
                .isInstanceOf(PostProductionException.class);
        verifyNoInteractions(foley, music);
    }

    @Test
    void anUploadWithNoSoundIsRefusedAndNotStored() {
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(true, false, new BigDecimal("2"), 640, 360));

        assertThatThrownBy(() -> service.upload(tenantId, projectId, userId, shotId, SoundLayerKind.SOUND_EFFECT, 0,
                new MockMultipartFile("file", "silent.mp4", "video/mp4", new byte[2048])))
                .isInstanceOf(PostProductionException.class)
                .hasMessageContaining("no sound");
        verify(objectStore, never()).upload(any(), any(), any());
    }

    @Test
    void movingLevellingAndSwitchingOffChangeOnlyWhatIsSent() {
        SoundLayer layer = SoundLayer.builder().layerId(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                .shotId(shotId).kind(SoundLayerKind.SOUND_EFFECT).source(SoundLayerSource.GENERATED)
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
    void theExtensionComesFromTheUrlPathNotItsQuery() {
        assertThat(SoundLayerService.extensionOf("https://fal.media/files/bell.WAV?token=a.b")).isEqualTo(".wav");
        assertThat(SoundLayerService.extensionOf("https://fal.media/files/bell")).isEqualTo(".mp3");
        assertThat(SoundLayerService.extensionOf("my bell.exe")).isEqualTo(".mp3");
        assertThat(SoundLayerService.extensionOf(null)).isEqualTo(".mp3");
    }
}
