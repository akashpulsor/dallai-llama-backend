package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ContinuityBible;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContinuityBibleRepository extends JpaRepository<ContinuityBible, UUID> {

    Optional<ContinuityBible> findByProjectId(UUID projectId);
}
