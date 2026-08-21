package com.dalai.llama.preprod.repository;

import com.dalai.llama.joblifecycle.StaleJobRepository;
import com.dalai.llama.preprod.domain.entity.GenerationJob;

import java.util.List;
import java.util.UUID;

public interface GenerationJobRepository extends StaleJobRepository<GenerationJob> {

    List<GenerationJob> findByShotIdOrderByCreatedAtDesc(UUID shotId);
}
