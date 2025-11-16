package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.Trunk;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrunkRepository extends JpaRepository<Trunk, String> {
    List<Trunk> findByTenantIdAndEnabledTrue(String tenantId);
}
