package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
import com.dalai.llama.preprod.domain.entity.ShotListJob;
import com.dalai.llama.preprod.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.preprod.repository.ShotListJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShotListSubmissionCommitTest {
    private final ShotListJobRepository jobs = mock(ShotListJobRepository.class);
    private final ShotListGenerationService generation = mock(ShotListGenerationService.class);
    private final ChatJobRequestedPublisher publisher = mock(ChatJobRequestedPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ShotListGenerationJobService service =
            new ShotListGenerationJobService(jobs, generation, publisher, transactions);
    private final UUID tenant = UUID.randomUUID(), project = UUID.randomUUID();
    private ShotListJob job;

    @BeforeEach
    void setup() {
        job = ShotListJob.builder().id(UUID.randomUUID()).tenantId(tenant).projectId(project)
                .status(ShotListJobStatus.SUCCEEDED).llmJobIdempotencyKey("key")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(generation.prepareJob(tenant, project)).thenReturn(
                new ShotListGenerationService.ShotListJobPreparation(null, "key"));
        when(jobs.findByLlmJobIdempotencyKey("key")).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void cachedCompletionCannotArriveBeforePendingCommit() {
        doAnswer(call -> { assertThat(job.getStatus()).isEqualTo(ShotListJobStatus.PENDING); return null; })
                .when(publisher).publish(any());
        service.submit(tenant, project);
        var order = inOrder(jobs, transactions, publisher);
        order.verify(jobs).save(job);
        order.verify(transactions).commit(any());
        order.verify(publisher).publish(any());
    }

    @Test
    void failedCommitDoesNotPublish() {
        doThrow(new IllegalStateException("commit failed")).when(transactions).commit(any());
        assertThatThrownBy(() -> service.submit(tenant, project)).hasMessage("commit failed");
        verifyNoInteractions(publisher);
    }

    @Test
    void brokerFailureIsRecordedInsteadOfLeavingPending() {
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(any());
        assertThatThrownBy(() -> service.submit(tenant, project)).hasMessage("broker unavailable");
        assertThat(job.getStatus()).isEqualTo(ShotListJobStatus.FAILED);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getErrorMessage()).contains("broker unavailable");
        verify(transactions, times(2)).commit(any());
    }

    @Test
    void publicationFailureDoesNotOverwriteACompletedJob() {
        doAnswer(call -> { job.setStatus(ShotListJobStatus.SUCCEEDED); throw new IllegalStateException("late failure"); })
                .when(publisher).publish(any());
        assertThatThrownBy(() -> service.submit(tenant, project)).hasMessage("late failure");
        assertThat(job.getStatus()).isEqualTo(ShotListJobStatus.SUCCEEDED);
    }
}
