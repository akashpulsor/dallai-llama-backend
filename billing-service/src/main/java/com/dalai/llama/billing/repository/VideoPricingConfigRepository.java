package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.VideoPricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoPricingConfigRepository extends JpaRepository<VideoPricingConfig, Short> {
}
