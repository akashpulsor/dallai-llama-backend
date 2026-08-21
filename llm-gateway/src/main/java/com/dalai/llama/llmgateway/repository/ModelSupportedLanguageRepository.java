package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.ModelSupportedLanguage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelSupportedLanguageRepository extends JpaRepository<ModelSupportedLanguage, ModelSupportedLanguage.Id> {

    List<ModelSupportedLanguage> findByIdModelId(String modelId);
}
