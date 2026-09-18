package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.domain.entity.FilmRenderStatus;
import com.dalai.llama.postprod.kafka.FilmAssemblyRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import com.dalai.llama.postprod.repository.ShotClipVersionRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Telling a creator what they are actually waiting for.
 *
 * <p>Films are joined one at a time across every tenant, so someone third in line sees exactly the
 * spinner someone being worked on right now sees. Only the server can tell them apart.
 */
class FilmQueueWaitTest {

    private final FilmRenderRepository repository = mock(FilmRenderRepository.class);

    private final FilmAssemblyService service = new FilmAssemblyService(
            repository, mock(FilmAssemblyRequestedPublisher.class), mock(ShotClipVersionRepository.class),
            mock(PreProductionClient.class), mock(VideoGenerationClient.class),
            mock(ShotClipVersionService.class), mock(FfmpegClipProcessor.class), mock(ClipObjectStore.class));

    private static FilmRender render(FilmRenderStatus status, OffsetDateTime createdAt) {
        return FilmRender.builder()
                .renderId(UUID.randomUUID()).tenantId(UUID.randomUUID()).projectId(UUID.randomUUID())
                .status(status).createdAt(createdAt).build();
    }

    /** startedAt to completedAt -- the encode, with nobody else's queueing folded in. */
    private static FilmRender finishedTaking(long seconds) {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        return FilmRender.builder()
                .renderId(UUID.randomUUID()).status(FilmRenderStatus.COMPLETED)
                .createdAt(start.minusMinutes(30))
                .startedAt(start)
                .completedAt(start.plusSeconds(seconds))
                .build();
    }

    @Test
    void countsEveryTenantsFilmsAhead() {
        FilmRender mine = render(FilmRenderStatus.QUEUED, OffsetDateTime.now());
        when(repository.countByStatusAndCreatedAtLessThan(eq(FilmRenderStatus.QUEUED), any()))
                .thenReturn(3L);
        when(repository.recentFinished(eq(FilmRenderStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(finishedTaking(60), finishedTaking(60), finishedTaking(60)));

        FilmAssemblyService.QueueWait wait = service.queueWait(mine);

        assertEquals(3, wait.filmsAhead(),
                "counting only this tenant's films would tell someone who is fourth that they are next");
        // Three ahead, one being worked on, and this one: five minutes at a minute each.
        assertEquals(300L, wait.estimatedWaitSeconds());
    }

    @Test
    void saysNothingRatherThanGuessingBeforeAnyJoinHasFinished() {
        FilmRender mine = render(FilmRenderStatus.QUEUED, OffsetDateTime.now());
        when(repository.countByStatusAndCreatedAtLessThan(eq(FilmRenderStatus.QUEUED), any()))
                .thenReturn(2L);
        when(repository.recentFinished(eq(FilmRenderStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of());

        FilmAssemblyService.QueueWait wait = service.queueWait(mine);

        assertEquals(2, wait.filmsAhead());
        assertNull(wait.estimatedWaitSeconds(), "a made-up number is worse than an honest unknown");
    }

    /** One pathological film must not drag every estimate after it. */
    @Test
    void usesTheMiddleJoinNotTheAverage() {
        FilmRender mine = render(FilmRenderStatus.QUEUED, OffsetDateTime.now());
        when(repository.countByStatusAndCreatedAtLessThan(eq(FilmRenderStatus.QUEUED), any()))
                .thenReturn(0L);
        when(repository.recentFinished(eq(FilmRenderStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(finishedTaking(30), finishedTaking(30), finishedTaking(3000)));

        FilmAssemblyService.QueueWait wait = service.queueWait(mine);

        // Median is 30s, so 2 x 30. A mean would have said 1020 x 2.
        assertEquals(60L, wait.estimatedWaitSeconds());
    }

    @Test
    void aFilmBeingWorkedOnIsNotWaitingBehindAnything() {
        FilmRender mine = render(FilmRenderStatus.PROCESSING, OffsetDateTime.now());

        FilmAssemblyService.QueueWait wait = service.queueWait(mine);

        assertEquals(0, wait.filmsAhead());
        assertNull(wait.estimatedWaitSeconds());
    }
}
