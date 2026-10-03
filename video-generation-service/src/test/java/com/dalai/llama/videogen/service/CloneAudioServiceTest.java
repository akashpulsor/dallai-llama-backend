package com.dalai.llama.videogen.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dalai.llama.videogen.service.dialoguefit.AudioDurationProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CloneAudioServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MinioClient minio = mock(MinioClient.class);
    private final VideoAssetPersistenceService assets = mock(VideoAssetPersistenceService.class);
    private final AudioDurationProbe durationProbe = mock(AudioDurationProbe.class);
    private final CloneAudioService service = new CloneAudioService(jdbc, minio, assets, durationProbe, "media");

    @Test
    void recloneUsesSameDatabaseResourceAndFreshAudioObject() throws Exception {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), shot = UUID.randomUUID(), beat = UUID.randomUUID();
        when(assets.presignedUrl(eq("media"), anyString())).thenAnswer(call -> "https://media/" + call.getArgument(1));
        String first = service.save(tenant, project, shot, beat, "Hi", "cloned", "voice1", "data:audio/mpeg;base64,AQID");
        String second = service.save(tenant, project, shot, beat, "Hi", "cloned", "voice2", "data:audio/mpeg;base64,BAUG");
        assertThat(second).isNotEqualTo(first);
        ArgumentCaptor<PutObjectArgs> upload = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio, times(2)).putObject(upload.capture());
        assertThat(upload.getValue().contentType()).isEqualTo("audio/mpeg");
        assertThat(upload.getAllValues().get(0).object()).isNotEqualTo(upload.getValue().object());
        ArgumentCaptor<String> upsert = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(upsert.capture(), eq(tenant), eq(project), eq(beat), eq(shot), eq(beat),
                eq("Hi"), eq("cloned"), anyString(), eq("media"), anyString(), any());
        assertThat(upsert.getValue())
                .contains("ON CONFLICT")
                .contains("rejected = false")
                .contains("rejected_at = NULL");
    }

    @Test
    void invalidAudioCannotReplaceSavedClip() {
        assertThatThrownBy(() -> service.save(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "Hi", "cloned", "voice", "data:audio/mpeg;base64,!!!")).isInstanceOf(VideoGenException.class);
        verifyNoInteractions(jdbc, minio, assets);
    }
}
