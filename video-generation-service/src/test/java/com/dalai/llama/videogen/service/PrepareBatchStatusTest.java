package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.PrepareBatchJobStatus;
import com.dalai.llama.videogen.domain.entity.PrepareBatchJob;
import com.dalai.llama.videogen.kafka.PrepareBatchRequestedPublisher;
import com.dalai.llama.videogen.repository.PrepareBatchJobRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The page polls the latest batch. A batch whose event was lost sat PENDING for six days and the
 * page showed "Preparing…" throughout -- the poll itself must release a batch that stopped moving.
 */
class PrepareBatchStatusTest {

    private final PrepareBatchJobRepository repository = mock(PrepareBatchJobRepository.class);
    private final PrepareBatchJobService service = new PrepareBatchJobService(repository, mock(PrepareBatchRequestedPublisher.class));
    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    private PrepareBatchJob latest(PrepareBatchJobStatus status, OffsetDateTime lastProgress) {
        PrepareBatchJob job = PrepareBatchJob.builder().id(UUID.randomUUID()).tenantId(tenant).projectId(project)
                .status(status).createdAt(lastProgress).updatedAt(lastProgress).build();
        when(repository.findFirstByProjectIdOrderByCreatedAtDesc(project)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void aBatchThatNeverStartedIsReleasedWhenThePagePollsIt() {
        PrepareBatchJob stuck = latest(PrepareBatchJobStatus.PENDING, OffsetDateTime.now().minusDays(6));

        assertThat(service.latestForProject(tenant, project)).contains(stuck);

        assertThat(stuck.getStatus()).isEqualTo(PrepareBatchJobStatus.FAILED);
        assertThat(stuck.getErrorMessage()).contains("Abandoned");
        verify(repository).save(stuck);
    }

    @Test
    void aBatchStillMakingProgressIsLeftRunning() {
        PrepareBatchJob running = latest(PrepareBatchJobStatus.RUNNING, OffsetDateTime.now().minusMinutes(2));

        service.latestForProject(tenant, project);

        assertThat(running.getStatus()).isEqualTo(PrepareBatchJobStatus.RUNNING);
        verify(repository, never()).save(any());
    }
}
