package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.DubbingJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DubbingJobRepository extends JpaRepository<DubbingJob, UUID> {

    List<DubbingJob> findByStatusAndProcessingStartedAtBefore(PostProductionStatus status, OffsetDateTime cutoff);
}
