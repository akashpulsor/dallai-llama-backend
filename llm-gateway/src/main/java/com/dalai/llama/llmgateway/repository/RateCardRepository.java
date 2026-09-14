package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {

    List<RateCard> findByModelIdOrderByEffectiveFromDesc(String modelId);

    Optional<RateCard> findFirstByModelIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String modelId, OffsetDateTime at);

    /** The rate for one render tier, e.g. Wan at 480p. */
    Optional<RateCard> findFirstByModelIdAndResolutionAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            String modelId, String resolution, OffsetDateTime at);

    /** Every currently-effective rate for a model, tiers included -- used to fall back to the
     * dearest known tier when the requested one has no row, so an unpriced tier cannot be billed
     * at some other tier's cheaper rate. */
    List<RateCard> findByModelIdAndEffectiveFromLessThanEqual(String modelId, OffsetDateTime at);
}
