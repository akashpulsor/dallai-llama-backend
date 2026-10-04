package com.dalai.llama.llmgateway.service.ratecard;

import com.dalai.llama.llmgateway.domain.entity.RateCard;
import com.dalai.llama.llmgateway.dto.RateCardView;
import com.dalai.llama.llmgateway.repository.RateCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the rates the gateway charges at, for services that estimate what a piece of work will
 * cost. Raw rates only: what an estimate does with them is the caller's business.
 */
@Service
public class RateCardQueryService {

    private final RateCardRepository rateCardRepository;

    public RateCardQueryService(RateCardRepository rateCardRepository) {
        this.rateCardRepository = rateCardRepository;
    }

    /** The newest effective rate of each tier of the model; empty for an unknown model. */
    @Transactional(readOnly = true)
    public List<RateCardView> current(String modelId) {
        Map<String, RateCard> newestByTier = new LinkedHashMap<>();
        rateCardRepository.findByModelIdAndEffectiveFromLessThanEqual(modelId, OffsetDateTime.now()).stream()
                .sorted(Comparator.comparing(RateCard::getEffectiveFrom).reversed())
                .forEach(rate -> newestByTier.putIfAbsent(Objects.toString(rate.getResolution(), ""), rate));
        return newestByTier.values().stream()
                .map(rate -> new RateCardView(rate.getModelId(), rate.getResolution(), rate.getPerSecondCost(),
                        rate.getInputTokenCost(), rate.getOutputTokenCost(), rate.getCurrency()))
                .toList();
    }
}
