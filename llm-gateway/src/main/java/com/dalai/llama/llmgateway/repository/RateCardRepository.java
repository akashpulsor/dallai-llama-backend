package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {

    List<RateCard> findByModelIdOrderByEffectiveFromDesc(String modelId);

    Optional<RateCard> findFirstByModelIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String modelId, OffsetDateTime at);
}
