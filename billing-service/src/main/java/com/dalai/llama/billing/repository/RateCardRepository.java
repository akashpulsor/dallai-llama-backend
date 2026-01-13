package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RateCardRepository extends JpaRepository<RateCard, UUID> {

    @Query("SELECT rc FROM RateCard rc WHERE rc.ratePlanId = :ratePlanId AND rc.metric = :metric")
    List<RateCard> findByRatePlanIdAndMetric(@Param("ratePlanId") UUID ratePlanId, @Param("metric") String metric);

    List<RateCard> findByRatePlanId(UUID ratePlanId);
}
