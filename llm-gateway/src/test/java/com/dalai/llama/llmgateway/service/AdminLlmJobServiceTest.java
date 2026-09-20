package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.JobStatus;
import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.RetryLlmJobResponse;
import com.dalai.llama.llmgateway.kafka.ChatJobRequestedEvent;
import com.dalai.llama.llmgateway.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards the admin retry decision matrix -- the exact one that would decide whether an operator
 * click can safely re-dispatch Pragya's Gemini-timeout shot-list job, or accidentally overwrite a
 * COMPLETED row / duplicate an in-flight PROCESSING one. */
class AdminLlmJobServiceTest {

    private final LlmJobRepository jobs = mock(LlmJobRepository.class);
    private final ChatJobRequestedPublisher publisher = mock(ChatJobRequestedPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdminLlmJobService service = new AdminLlmJobService(jobs, publisher, objectMapper);

    private LlmJob failedJobWithRequest() throws Exception {
        ChatRequest req = new ChatRequest(
                "1",
                "gemini-2.5-flash",
                List.of(new ChatMessage("user", "hi")),
                null, null, null, null, null, null);
        return LlmJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId("tenant-1")
                .idempotencyKey("shot-list-generate-abc")
                .status(JobStatus.FAILED)
                .mode("async")
                .schemaVersion("1")
                .attemptCount(3)
                .requestContent(objectMapper.writeValueAsString(req))
                .build();
    }

    @Test
    void retryPublishesEventForRetryableTerminalRow() throws Exception {
        LlmJob job = failedJobWithRequest();
        when(jobs.findById(job.getJobId())).thenReturn(Optional.of(job));

        RetryLlmJobResponse response = service.retry(job.getJobId());

        ArgumentCaptor<ChatJobRequestedEvent> captor = ArgumentCaptor.forClass(ChatJobRequestedEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(job.getIdempotencyKey());
        assertThat(captor.getValue().tenantId()).isEqualTo(job.getTenantId());
        assertThat(captor.getValue().request().modelId()).isEqualTo("gemini-2.5-flash");
        assertThat(response.previousStatus()).isEqualTo("FAILED");
    }

    @Test
    void retryRefusesInFlightProcessingJob() throws Exception {
        LlmJob job = failedJobWithRequest();
        job.setStatus(JobStatus.PROCESSING);
        when(jobs.findById(job.getJobId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retry(job.getJobId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("still PROCESSING");
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryRefusesAlreadyCompletedJobToProtectResult() throws Exception {
        LlmJob job = failedJobWithRequest();
        job.setStatus(JobStatus.COMPLETED);
        when(jobs.findById(job.getJobId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retry(job.getJobId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already COMPLETED");
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }
}
