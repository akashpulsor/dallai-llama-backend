package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.ModelCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelCapabilityRepository extends JpaRepository<ModelCapability, Long> {

    List<ModelCapability> findByModelId(String modelId);
}
