package com.dalai.llama.tenant.showcase.repository;

import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.entity.OfficialUploadJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialUploadJobRepository extends JpaRepository<OfficialUploadJob, UUID> {

    Optional<OfficialUploadJob> findByProjectId(UUID projectId);

    Optional<OfficialUploadJob> findFirstByStatusOrderByCreatedAtAsc(OfficialUploadStatus status);

    List<OfficialUploadJob> findByStatusIn(List<OfficialUploadStatus> statuses);
}
