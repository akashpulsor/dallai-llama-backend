package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
import com.dalai.llama.preprod.domain.entity.ShotListJob;
import com.dalai.llama.preprod.dto.ShotListJobView;
import com.dalai.llama.preprod.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.preprod.repository.ShotListJobRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Guards the endpoint the UI hits on mount so a page reload after a FAILED shot-list run
 * rehydrates the failure banner instead of showing the empty-shots panel a fresh project shows.
 * The specific regression this catches is Pragya's project: a shot_list_job existed but the UI
 * had no way to see it. */
class ShotListGenerationJobServiceLatestTest {

    private final ShotListJobRepository jobs = mock(ShotListJobRepository.class);
    private final ShotListGenerationService generation = mock(ShotListGenerationService.class);
    private final ChatJobRequestedPublisher publisher = mock(ChatJobRequestedPublisher.class);
    private final ShotListGenerationJobService service =
            new ShotListGenerationJobService(jobs, generation, publisher);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @Test
    void latestReturnsMappedViewWhenAJobExists() {
        OffsetDateTime now = OffsetDateTime.now();
        ShotListJob job = ShotListJob.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                .status(ShotListJobStatus.FAILED)
                .errorMessage("Gemini call failed: Did not observe any item or terminal signal within 90000ms in 'flatMap' (and no fallback has been configured)")
                .createdAt(now).completedAt(now).updatedAt(now)
                .build();
        when(jobs.findTopByProjectIdAndTenantIdOrderByCreatedAtDesc(projectId, tenantId))
                .thenReturn(Optional.of(job));

        Optional<ShotListJobView> view = service.latest(tenantId, projectId);

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo(ShotListJobStatus.FAILED);
        assertThat(view.get().errorMessage()).contains("Did not observe any item");
        assertThat(view.get().projectId()).isEqualTo(projectId);
    }

    @Test
    void latestReturnsEmptyWhenNoJobExists() {
        when(jobs.findTopByProjectIdAndTenantIdOrderByCreatedAtDesc(projectId, tenantId))
                .thenReturn(Optional.empty());

        assertThat(service.latest(tenantId, projectId)).isEmpty();
    }
}
