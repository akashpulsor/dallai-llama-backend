package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.Did;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DidRepository extends JpaRepository<Did, String> {
    Optional<Did> findByTenantIdAndNumber(String tenantId, String number);
}
