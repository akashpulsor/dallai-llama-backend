package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, String> {
}
