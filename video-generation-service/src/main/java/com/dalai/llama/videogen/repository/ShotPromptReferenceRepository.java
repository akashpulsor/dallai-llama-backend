package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotPromptReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShotPromptReferenceRepository extends JpaRepository<ShotPromptReference, Long> {

    List<ShotPromptReference> findByPromptId(UUID promptId);
}
