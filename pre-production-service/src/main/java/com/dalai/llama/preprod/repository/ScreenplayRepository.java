package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.Screenplay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreenplayRepository extends JpaRepository<Screenplay, UUID> {

    Optional<Screenplay> findTopByProjectIdOrderByVersionDesc(UUID projectId);

    Optional<Screenplay> findByProjectIdAndVersion(UUID projectId, Integer version);

    List<Screenplay> findByProjectIdOrderByVersionAsc(UUID projectId);
}
