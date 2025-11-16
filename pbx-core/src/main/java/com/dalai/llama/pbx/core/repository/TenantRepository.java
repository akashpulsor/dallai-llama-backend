package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, String> {
    Optional<Tenant> findByName(String name);
}
