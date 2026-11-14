package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.UploadStamp;
import com.dalai.llama.postprod.kafka.FilmStampRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmStampRequestedPublisher;
import com.dalai.llama.postprod.repository.UploadStampRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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

/** A creator's uploaded film gets the same Dalaillama stamp as films, on the same one-at-a-time consumer. */
class UploadStampServiceTest {

    @TempDir Path work;

    private final UploadStampRepository stamps = mock(UploadStampRepository.class);
    private final FilmStampRequestedPublisher publisher = mock(FilmStampRequestedPublisher.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ClipObjectStore store = mock(ClipObjectStore.class);
    private final UploadStampService service = new UploadStampService(stamps, publisher, ffmpeg, store);
    private UploadStamp upload;

    @BeforeEach
    void setUp() {
        upload = UploadStamp.builder().id(UUID.randomUUID()).tenantId(UUID.randomUUID())
                .sourceBucket("creator-assets").sourceKey("showcase/t/film.mp4").width(1920).attempts(0).build();
        when(stamps.findById(upload.getId())).thenReturn(Optional.of(upload));
        when(stamps.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ffmpeg.createWorkDir(anyString())).thenReturn(work);
        when(store.internalPresignedUrl("creator-assets", "showcase/t/film.mp4")).thenReturn("http://minio/upload");
        when(store.bucket()).thenReturn("films");
    }

    @Test
    void askingTwiceIsOneRequest_andItReadsPendingUntilMade() {
        UUID id = UUID.randomUUID();
        when(stamps.findById(id)).thenReturn(Optional.empty());
        UploadStampService.StampRequest request = new UploadStampService.StampRequest(upload.getTenantId(), "creator-assets", "k.mp4", 1080);

        assertThat(service.request(id, request)).isEqualTo(new UploadStampService.StampView("PENDING", null, null));
        verify(stamps).save(any());

        when(stamps.findById(id)).thenReturn(Optional.of(upload));
        service.request(id, request);
        verify(stamps).save(any()); // still once
    }

    @Test
    void theSweepQueuesDueUploadsOnTheFilmsConsumer() {
        when(stamps.findDue(any(), any(), eq(UploadStampService.MAX_ATTEMPTS), anyInt())).thenReturn(List.of(upload));

        assertThat(service.sweep()).isEqualTo(1);

        assertThat(upload.getStatus()).isEqualTo("PENDING");
        assertThat(upload.getAttempts()).isEqualTo(1);
        ArgumentCaptor<FilmStampRequestedEvent> event = ArgumentCaptor.forClass(FilmStampRequestedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().uploadStampId()).isEqualTo(upload.getId());
        assertThat(event.getValue().renderId()).isNull();
        assertThat(event.getValue().key()).isEqualTo(upload.getId().toString());
    }

    @Test
    void stampingBurnsTheMarkSizedForTheUpload_andHandsBackTheCopy() {
        service.stamp(upload.getId());

        verify(ffmpeg).stamp(eq("http://minio/upload"), eq(work.resolve("stamp.png")), eq(346), eq(58), eq(work.resolve("stamped.mp4")));
        String key = "uploads/" + upload.getTenantId() + "/" + upload.getId() + "-stamped.mp4";
        verify(store).upload(key, work.resolve("stamped.mp4"), "video/mp4");
        when(store.presignedUrl("films", key)).thenReturn("https://media/stamped");
        assertThat(service.find(upload.getId())).contains(new UploadStampService.StampView("DONE", "https://media/stamped", null));
    }

    @Test
    void aFailureRetries_andReadsFailedOnlyOnceAttemptsRunOut() {
        doThrow(new ClipProcessingException("ffmpeg failed: boom")).when(ffmpeg).stamp(anyString(), any(), anyInt(), anyInt(), any());
        upload.setAttempts(1);

        service.stamp(upload.getId());

        assertThat(upload.getStatus()).isEqualTo("FAILED");
        verify(store, never()).upload(anyString(), any(), anyString());
        assertThat(service.find(upload.getId())).contains(new UploadStampService.StampView("PENDING", null, null));
        upload.setAttempts(UploadStampService.MAX_ATTEMPTS);
        assertThat(service.find(upload.getId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(service.find(upload.getId()).orElseThrow().error()).contains("boom");
    }
}
