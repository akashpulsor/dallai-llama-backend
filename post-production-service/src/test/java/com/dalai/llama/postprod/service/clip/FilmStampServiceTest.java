package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.kafka.FilmStampRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmStampRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every published film gets a Dalaillama-stamped copy, queued by the sweep and made by the consumer. */
class FilmStampServiceTest {

    @TempDir Path work;

    private final FilmRenderRepository renders = mock(FilmRenderRepository.class);
    private final FilmStampRequestedPublisher publisher = mock(FilmStampRequestedPublisher.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ClipObjectStore store = mock(ClipObjectStore.class);
    private final FilmStampService service = new FilmStampService(renders, publisher, ffmpeg, store);
    private FilmRender film;

    @BeforeEach
    void setUp() {
        film = FilmRender.builder().renderId(UUID.randomUUID()).tenantId(UUID.randomUUID()).projectId(UUID.randomUUID())
                .bucket("films").objectKey("films/p/r.mp4").width(1080).height(1920).published(true).build();
        when(renders.findById(film.getRenderId())).thenReturn(Optional.of(film));
        when(ffmpeg.createWorkDir(anyString())).thenReturn(work);
        when(store.internalPresignedUrl("films", "films/p/r.mp4")).thenReturn("http://minio/film");
        when(store.bucket()).thenReturn("films");
    }

    @Test
    void theMarkIsAboutAFifthOfTheWidthInsetThreePercentAndEven() {
        assertThat(FfmpegClipProcessor.stampGeometry(1080)).containsExactly(238, 32);
        assertThat(FfmpegClipProcessor.stampGeometry(1920)).containsExactly(422, 58);
        assertThat(FfmpegClipProcessor.stampGeometry(400)).containsExactly(160, 12);
        assertThat(FfmpegClipProcessor.stampFilter(238, 32))
                .isEqualTo("[1:v]scale=238:-2[mark];[0:v][mark]overlay=main_w-overlay_w-32:main_h-overlay_h-32:format=auto[v]");
    }

    @Test
    void theSweepQueuesDueFilmsAndCountsTheAttempt() {
        when(renders.findDueForStamp(any(), any(), eq(FilmStampService.MAX_ATTEMPTS), anyInt())).thenReturn(List.of(film));

        assertThat(service.sweep()).isEqualTo(1);

        assertThat(film.getStampStatus()).isEqualTo("PENDING");
        assertThat(film.getStampAttempts()).isEqualTo(1);
        assertThat(film.getStampRequestedAt()).isNotNull();
        ArgumentCaptor<FilmStampRequestedEvent> event = ArgumentCaptor.forClass(FilmStampRequestedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().renderId()).isEqualTo(film.getRenderId());
    }

    @Test
    void stampingStoresTheCopyNextToTheFilmAndMarksItDone() {
        service.stamp(film.getRenderId());

        verify(ffmpeg).stamp(eq("http://minio/film"), eq(work.resolve("stamp.png")), eq(238), eq(32), eq(work.resolve("stamped.mp4")));
        assertThat(Files.exists(work.resolve("stamp.png"))).as("the mark ships in the jar").isTrue();
        String key = "films/" + film.getProjectId() + "/" + film.getRenderId() + "-stamped.mp4";
        verify(store).upload(key, work.resolve("stamped.mp4"), "video/mp4");
        assertThat(film.getStampedObjectKey()).isEqualTo(key);
        assertThat(film.getStampStatus()).isEqualTo("DONE");
        when(store.presignedUrl("films", key)).thenReturn("https://minio/stamped");
        assertThat(service.stampedUrl(film)).isEqualTo("https://minio/stamped");
    }

    @Test
    void aFailedStampIsRecordedForTheSweepToRetry() {
        doThrow(new ClipProcessingException("ffmpeg failed: boom")).when(ffmpeg).stamp(anyString(), any(), anyInt(), anyInt(), any());

        service.stamp(film.getRenderId());

        assertThat(film.getStampStatus()).isEqualTo("FAILED");
        assertThat(film.getStampError()).contains("boom");
        assertThat(film.getStampedObjectKey()).isNull();
        verify(store, never()).upload(anyString(), any(), anyString());
        assertThat(service.stampedUrl(film)).isNull();
    }
}
