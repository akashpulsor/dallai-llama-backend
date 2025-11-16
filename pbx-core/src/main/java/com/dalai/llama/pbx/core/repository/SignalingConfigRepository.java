package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SignalingConfigRepository extends JpaRepository<SignalingConfig, String> {
    Optional<SignalingConfig> findByTenantId(String tenantId);
}
