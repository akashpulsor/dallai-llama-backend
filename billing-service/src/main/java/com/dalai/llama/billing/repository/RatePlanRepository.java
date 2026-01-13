package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.RatePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RatePlanRepository extends JpaRepository<RatePlan, UUID> {

    Optional<RatePlan> findByCode(String code);

    Optional<RatePlan> findByIsDefaultTrue();
}
