package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.ClipOrigin;
import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.kafka.FilmAssemblyRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import com.dalai.llama.postprod.repository.ShotClipVersionRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotSummary;
import com.dalai.llama.postprod.service.videogen.VideoGenShotJob;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A shot the client films: their footage becomes the shot's cut directly, and the film's readiness
 * says it is waiting on the client rather than on generation. */
class ClientFootageTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final ShotClipVersionRepository repository = mock(ShotClipVersionRepository.class);
    private final VideoGenerationClient videoGenerationClient = mock(VideoGenerationClient.class);
    private final FfmpegClipProcessor ffmpeg = mock(FfmpegClipProcessor.class);
    private final ClipObjectStore objectStore = mock(ClipObjectStore.class);
    private final PreProductionClient preProductionClient = mock(PreProductionClient.class);
    private final ShotClipVersionService service =
            new ShotClipVersionService(repository, videoGenerationClient, ffmpeg, objectStore, preProductionClient);
    private final Map<UUID, ShotClipVersion> saved = new HashMap<>();

    private final ShotClipVersionService.Context context =
            new ShotClipVersionService.Context(tenantId, projectId, shotId, "shot-06-003", UUID.randomUUID());
    private final MockMultipartFile footage =
            new MockMultipartFile("file", "testimonials.mov", "video/quicktime", new byte[4096]);

    private void wireStorage(boolean playable) throws Exception {
        when(ffmpeg.createWorkDir(any())).thenReturn(Files.createTempDirectory("client-footage-test"));
        when(ffmpeg.probe(any())).thenReturn(new ClipProbe(playable, true, BigDecimal.valueOf(7), 1920, 1080));
        when(objectStore.objectKeyFor(shotId, 1)).thenReturn("clips/" + shotId + "/v1.mp4");
        when(objectStore.bucket()).thenReturn("post-production");
        when(repository.highestVersionNumber(shotId)).thenReturn(0);
        when(repository.save(any())).thenAnswer(call -> {
            ShotClipVersion version = call.getArgument(0);
            saved.put(version.getVersionId(), version);
            return version;
        });
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(saved.get(call.<UUID>getArgument(0))));
    }

    @Test
    void theClientsFootageBecomesTheShotsCutWithoutAnyGeneratedClip() throws Exception {
        wireStorage(true);
        when(repository.findByShotIdAndStatus(shotId, ClipVersionStatus.ACTIVE)).thenReturn(Optional.empty());

        ShotClipVersion cut = service.uploadClientFootage(context, footage);

        assertThat(cut.getOrigin()).isEqualTo(ClipOrigin.CLIENT_FOOTAGE);
        assertThat(cut.getStatus()).isEqualTo(ClipVersionStatus.ACTIVE);
        assertThat(cut.getSourceJobId()).isNull();
        assertThat(cut.getVersionNumber()).isEqualTo(1);
        verify(objectStore).upload(any(), any());
        // Nothing generated exists for this shot, so nothing generated is looked up.
        verifyNoInteractions(videoGenerationClient);
    }

    @Test
    void newFootageReplacesTheCutTheShotHad() throws Exception {
        wireStorage(true);
        ShotClipVersion previous = ShotClipVersion.builder().versionId(UUID.randomUUID()).tenantId(tenantId)
                .shotId(shotId).origin(ClipOrigin.CLIENT_FOOTAGE).status(ClipVersionStatus.ACTIVE).build();
        when(repository.findByShotIdAndStatus(shotId, ClipVersionStatus.ACTIVE)).thenReturn(Optional.of(previous));

        service.uploadClientFootage(context, footage);

        assertThat(previous.getStatus()).isEqualTo(ClipVersionStatus.SUPERSEDED);
    }

    @Test
    void aFileWithNoVideoIsRefusedAndNothingIsStored() throws Exception {
        wireStorage(false);

        assertThatThrownBy(() -> service.uploadClientFootage(context, footage))
                .isInstanceOf(ClipProcessingException.class)
                .hasMessageContaining("no usable video");
        verify(objectStore, never()).upload(any(), any());
    }

    @Test
    void readinessNamesTheShotsWaitingOnTheClient() {
        UUID cut = UUID.randomUUID(), generated = UUID.randomUUID(), notMade = UUID.randomUUID(), clientShot = UUID.randomUUID();
        when(preProductionClient.listShots(tenantId, projectId)).thenReturn(List.of(
                new PreProductionShotSummary(cut, "S1", 1, 4, false),
                new PreProductionShotSummary(generated, "S2", 2, 4, false),
                new PreProductionShotSummary(notMade, "S3", 3, 4, false),
                new PreProductionShotSummary(clientShot, "S4", 4, 7, true)));
        when(repository.findByProjectIdAndStatus(projectId, ClipVersionStatus.ACTIVE)).thenReturn(List.of(
                ShotClipVersion.builder().versionId(UUID.randomUUID()).shotId(cut).status(ClipVersionStatus.ACTIVE).build()));
        when(videoGenerationClient.listJobsForProject(tenantId, projectId)).thenReturn(List.of(
                new VideoGenShotJob(UUID.randomUUID(), "S2", "COMPLETED", null, false, null)));
        FilmAssemblyService films = new FilmAssemblyService(mock(FilmRenderRepository.class), mock(FilmAssemblyRequestedPublisher.class),
                repository, preProductionClient, videoGenerationClient, service, ffmpeg, objectStore,
                mock(com.dalai.llama.postprod.service.sound.SoundLayerService.class));

        FilmAssemblyService.Readiness readiness = films.readiness(tenantId, projectId);

        assertThat(readiness.missingShotRefs()).containsExactly("S3", "S4");
        assertThat(readiness.awaitingClientFootageShotRefs()).containsExactly("S4");
        assertThat(readiness.isReady()).isFalse();
    }
}
