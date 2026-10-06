package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import com.dalai.llama.postprod.repository.SoundLayerRepository;
import com.dalai.llama.postprod.service.AudioGenerationResult;
import com.dalai.llama.postprod.service.FoleyGenerationService;
import com.dalai.llama.postprod.service.MusicGenerationService;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The worker side: generate or pick up, probe, store -- and a failure written on the row. */
class SoundLayerProcessorTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final SoundLayerRepository repository = mock(SoundLayerRepository.class);
    private final FoleyGenerationService foley = mock(FoleyGenerationService.class);
    private final MusicGenerationService music = mock(MusicGenerationService.class);
    private final ShotClipVersionService clips = mock(ShotClipVersionService.class);
    private final ClipObjectStore objectStore = mock(ClipObjectStore.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final SoundLayerProcessor processor = new SoundLayerProcessor(repository, foley, music, clips, objectStore, ffmpeg);

    @BeforeEach
    void setUp() throws Exception {
        when(ffmpeg.createWorkDir(any())).thenReturn(Files.createTempDirectory("sound-layer-worker-test"));
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(false, true, new BigDecimal("3.2"), null, null));
        when(objectStore.bucket()).thenReturn("post-production");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private SoundLayer queued(SoundLayerKind kind, SoundLayerSource source) {
        SoundLayer layer = SoundLayer.builder().layerId(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                .shotId(UUID.randomUUID()).kind(kind).source(source).prompt("a single temple bell, long ring")
                .status(FrameExtractionStatus.QUEUED).included(true).build();
        when(repository.findById(layer.getLayerId())).thenReturn(Optional.of(layer));
        return layer;
    }

    @Test
    void aSoundEffectIsGeneratedProbedAndStoredOnTheWorker() {
        SoundLayer bell = queued(SoundLayerKind.SOUND_EFFECT, SoundLayerSource.GENERATED);
        when(foley.generateFoley(eq(tenantId), eq(projectId), eq("sound-layer-" + bell.getLayerId()), isNull(),
                eq("a single temple bell, long ring"), isNull()))
                .thenReturn(new AudioGenerationResult(UUID.randomUUID(), "https://fal.media/bell.wav?sig=1", BigDecimal.ONE));

        processor.process(bell.getLayerId());

        verify(clips).fetch(eq("https://fal.media/bell.wav?sig=1"), any());
        verify(objectStore).upload(eq("sound-layers/" + projectId + "/" + bell.getLayerId() + ".wav"), any(), eq("audio/wav"));
        assertThat(bell.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
        assertThat(bell.getDurationSeconds()).isEqualByComparingTo("3.2");
        assertThat(bell.getBucket()).isEqualTo("post-production");
        verifyNoInteractions(music);
    }

    @Test
    void musicIsGeneratedAtTheLengthAskedFor() {
        SoundLayer cue = queued(SoundLayerKind.MUSIC, SoundLayerSource.GENERATED);
        cue.setRequestedSeconds(12);
        when(music.generateMusic(eq(tenantId), eq(projectId), any(), eq(cue.getPrompt()), eq(12), isNull()))
                .thenReturn(new AudioGenerationResult(UUID.randomUUID(), "https://elevenlabs/cue.mp3", BigDecimal.ONE));

        processor.process(cue.getLayerId());

        assertThat(cue.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
        verifyNoInteractions(foley);
    }

    @Test
    void anUploadIsOnlyCheckedNotStoredAgain() {
        SoundLayer upload = queued(SoundLayerKind.SOUND_EFFECT, SoundLayerSource.UPLOADED);
        upload.setBucket("post-production");
        upload.setObjectKey("sound-layers/p/l.wav");

        processor.process(upload.getLayerId());

        verify(objectStore).download(eq("post-production"), eq("sound-layers/p/l.wav"), any());
        verify(objectStore, never()).upload(any(), any(), any());
        assertThat(upload.getStatus()).isEqualTo(FrameExtractionStatus.COMPLETED);
    }

    @Test
    void anUploadWithNoSoundFailsWithAReasonTheCreatorCanRead() {
        SoundLayer upload = queued(SoundLayerKind.SOUND_EFFECT, SoundLayerSource.UPLOADED);
        upload.setBucket("post-production");
        upload.setObjectKey("sound-layers/p/l.mp3");
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(true, false, new BigDecimal("2"), 640, 360));

        processor.process(upload.getLayerId());

        assertThat(upload.getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
        assertThat(upload.getError()).contains("no sound");
    }

    @Test
    void aFailedGenerationIsWrittenOnTheRowNotThrownBackToKafka() {
        SoundLayer bell = queued(SoundLayerKind.SOUND_EFFECT, SoundLayerSource.GENERATED);
        when(foley.generateFoley(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("fal.ai balance exhausted"));

        processor.process(bell.getLayerId());

        assertThat(bell.getStatus()).isEqualTo(FrameExtractionStatus.FAILED);
        assertThat(bell.getError()).isEqualTo("fal.ai balance exhausted");
        verify(ffmpeg).deleteQuietly(any());
    }

    @Test
    void aRedeliveryOfAFinishedLayerDoesNothing() {
        SoundLayer done = queued(SoundLayerKind.SOUND_EFFECT, SoundLayerSource.GENERATED);
        done.setStatus(FrameExtractionStatus.COMPLETED);

        processor.process(done.getLayerId());

        verifyNoInteractions(foley, music, ffmpeg);
    }
}
