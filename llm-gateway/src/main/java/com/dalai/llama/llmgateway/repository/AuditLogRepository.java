package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
