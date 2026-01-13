package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.AgentCapacity;
import com.dalai.llama.tenant.domain.entity.CompliancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentCapacityRepository extends JpaRepository<AgentCapacity, UUID> {
}
