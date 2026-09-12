package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.ProviderLanguageMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderLanguageMappingRepository extends JpaRepository<ProviderLanguageMapping, Long> {

    List<ProviderLanguageMapping> findByProviderIdAndLanguageCodeAndActiveTrue(String providerId, String languageCode);
}
