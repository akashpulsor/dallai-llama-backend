package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CastAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CastAssignmentRepository extends JpaRepository<CastAssignment, UUID> {

    List<CastAssignment> findByProjectId(UUID projectId);

    Optional<CastAssignment> findByProjectIdAndScriptCharacterId(UUID projectId, UUID scriptCharacterId);
}
