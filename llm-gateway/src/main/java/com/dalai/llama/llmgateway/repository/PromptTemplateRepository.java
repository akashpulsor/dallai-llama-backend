package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    Optional<PromptTemplate> findByTaskKeyAndActiveTrue(String taskKey);
}
