package com.dalai.llama.trendintel.repository;

import com.dalai.llama.trendintel.domain.entity.TrendReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrendReportRepository extends JpaRepository<TrendReport, UUID> {

    Optional<TrendReport> findByIdAndTenantId(UUID id, UUID tenantId);

    List<TrendReport> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
