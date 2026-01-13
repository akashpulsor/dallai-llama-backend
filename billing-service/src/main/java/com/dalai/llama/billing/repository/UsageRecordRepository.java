package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    List<UsageRecord> findByTenantId(UUID tenantId);
}
