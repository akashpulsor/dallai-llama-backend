package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.ClipOrigin;
import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.kafka.FilmAssemblyRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import com.dalai.llama.postprod.repository.ShotClipVersionRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotSummary;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * When the film-assembly event is allowed to leave.
 *
 * <p>This is the bug that lost every film ever requested. The event was published inside
 * {@code request()}'s transaction, so it reached Kafka while the film_render row was still
 * uncommitted and invisible to every other connection. The consumer -- another thread, another
 * connection -- looked the renderId up 300ms before the commit landed, found nothing, and dropped
 * the event. The film then sat QUEUED for ever and looked like a slow join. Production had one
 * queued, one dropped, none joined.
 *
 * <p>So the assertion is about ORDERING, not about publishing: nothing may be sent while the
 * transaction is still open.
 */
class FilmAssemblyQueueingTest {

    private final FilmRenderRepository filmRenderRepository = mock(FilmRenderRepository.class);
    private final FilmAssemblyRequestedPublisher publisher = mock(FilmAssemblyRequestedPublisher.class);
    private final ShotClipVersionRepository clipVersionRepository = mock(ShotClipVersionRepository.class);
    private final PreProductionClient preProductionClient = mock(PreProductionClient.class);
    private final VideoGenerationClient videoGenerationClient = mock(VideoGenerationClient.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();

    private FilmAssemblyService service;

    @BeforeEach
    void setUp() {
        service = new FilmAssemblyService(
                filmRenderRepository, publisher, clipVersionRepository, preProductionClient,
                videoGenerationClient, mock(ShotClipVersionService.class),
                mock(FfmpegClipProcessor.class), mock(ClipObjectStore.class));

        // One shot, already cut, so the project is ready to join.
        when(preProductionClient.listShots(tenantId, projectId))
                .thenReturn(List.of(new PreProductionShotSummary(shotId, "S1", 1, 4)));
        when(clipVersionRepository.findByProjectIdAndStatus(projectId, ClipVersionStatus.ACTIVE))
                .thenReturn(List.of(ShotClipVersion.builder()
                        .versionId(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                        .shotId(shotId).shotRef("S1").versionNumber(1)
                        .origin(ClipOrigin.GENERATED).status(ClipVersionStatus.ACTIVE)
                        .bucket("clips").objectKey("a.mp4").createdAt(OffsetDateTime.now())
                        .build()));
        when(filmRenderRepository.save(any(FilmRender.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // A real transaction would have one; the service registers its callback here.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesNothingUntilTheTransactionHasCommitted() {
        FilmRender render = service.request(tenantId, projectId, userId);

        // The row exists only inside the open transaction. Anything sent now is sent too early --
        // this is exactly the state in which the old code published.
        verifyNoInteractions(publisher);
        assertFalse(TransactionSynchronizationManager.getSynchronizations().isEmpty(),
                "request() must defer publishing to an after-commit callback");

        // Now let the transaction commit.
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(
                event -> event.renderId().equals(render.getRenderId())
                        && event.projectId().equals(projectId)
                        && event.tenantId().equals(tenantId.toString())));
    }

    @Test
    void refusesAProjectWithNothingToJoin() {
        when(preProductionClient.listShots(tenantId, projectId)).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(ClipProcessingException.class,
                () -> service.request(tenantId, projectId, userId));

        verify(publisher, never()).publish(any());
        assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size(),
                "a refused request must not leave a publish queued behind it");
    }
}
