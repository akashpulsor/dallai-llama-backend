package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.MarketingPlanMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketingPlanMessageRepository extends JpaRepository<MarketingPlanMessage, UUID> {

    List<MarketingPlanMessage> findByPlanIdOrderByCreatedAtAsc(UUID planId);
}
