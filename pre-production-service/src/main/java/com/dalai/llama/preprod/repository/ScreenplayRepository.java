package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.Screenplay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScreenplayRepository extends JpaRepository<Screenplay, UUID> {

    Optional<Screenplay> findByProjectId(UUID projectId);
}
