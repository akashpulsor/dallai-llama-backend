package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.GenerationThought;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationThoughtRepository extends JpaRepository<GenerationThought, UUID> {

    List<GenerationThought> findByShotIdOrderByCreatedAtAsc(UUID shotId);
}
