package com.dalai.llama.joblifecycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Template repository for {@link AbstractStaleJobReconciliationTask}. Each service's concrete
 * job repository extends this with its own entity type; Spring Data derives the query from the
 * entity's {@code status} / {@code processingStartedAt} properties same as any other
 * repository method. {@code @NoRepositoryBean} keeps Spring Data from instantiating this
 * template interface itself.
 */
@NoRepositoryBean
public interface StaleJobRepository<T extends TrackedJob> extends JpaRepository<T, UUID> {

    List<T> findByStatusAndProcessingStartedAtBefore(JobLifecycleStatus status, OffsetDateTime cutoff);
}
