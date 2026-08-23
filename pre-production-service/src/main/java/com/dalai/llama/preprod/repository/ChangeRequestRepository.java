package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {

    List<ChangeRequest> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<ChangeRequest> findByIdAndTenantId(UUID id, UUID tenantId);
}
