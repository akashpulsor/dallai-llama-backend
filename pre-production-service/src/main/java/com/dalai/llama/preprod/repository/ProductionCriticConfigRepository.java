package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ProductionCriticConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionCriticConfigRepository extends JpaRepository<ProductionCriticConfig, Short> {
}
