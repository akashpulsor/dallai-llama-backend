package com.dalai.llama.billing.service.rating;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.domain.entity.RateCard;
import com.dalai.llama.billing.domain.entity.RatePlan;
import com.dalai.llama.billing.domain.entity.enums.DestinationType;
import com.dalai.llama.billing.domain.exception.RatePlanNotFoundException;
import com.dalai.llama.billing.repository.RateCardRepository;
import com.dalai.llama.billing.repository.RatePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class RatingEngineImpl implements RatingEngine {

    private final RatePlanRepository ratePlanRepository;
    private final RateCardRepository rateCardRepository;
    private final DestinationClassifier destinationClassifier;
    private final BillableCalculator billableCalculator;

    @Override
    public void rate(Cdr cdr) {
        // 1. Get default rate plan
        RatePlan ratePlan = getDefaultRatePlan();

        // 2. Classify destination
        String destinationNumber = "OUTBOUND".equalsIgnoreCase(cdr.getDirection())
                ? cdr.getToNumber()
                : cdr.getFromNumber();
        DestinationType destType = destinationClassifier.classify(destinationNumber);

        // 3. Find applicable rate card for call
        String callMetric = "OUTBOUND".equalsIgnoreCase(cdr.getDirection())
                ? "OUTBOUND_CALL_MINUTES"
                : "INBOUND_CALL_MINUTES";

        RateCard callRateCard = findBestRateCard(ratePlan, callMetric, destType, destinationNumber)
                .orElseThrow(() -> new RatePlanNotFoundException(
                        "No rate card found for metric: " + callMetric + ", destination: " + destType
                ));

        // 4. Calculate billable seconds
        int billableSeconds = billableCalculator.calculate(
                cdr.getDurationSeconds(),
                callRateCard.getBillingIncrement(),
                callRateCard.getMinimumCharge()
        );

        // 5. Calculate call cost (rate is per minute, convert billable seconds to minutes)
        BigDecimal billableMinutes = BigDecimal.valueOf(billableSeconds)
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        BigDecimal callCost = callRateCard.getRatePerUnit()
                .multiply(billableMinutes)
                .setScale(4, RoundingMode.HALF_UP);

        // 6. Calculate AI costs
        BigDecimal aiCost = calculateAiCost(ratePlan, cdr);

        // 7. Update CDR with rating info
        cdr.markRated(
                ratePlan.getId(),
                destType,
                billableSeconds,
                callRateCard.getRatePerUnit(),
                callCost,
                aiCost
        );

        log.debug("Rated CDR {}: billable={}s, callCost={}, aiCost={}, total={}",
                cdr.getCallId(), billableSeconds, callCost, aiCost, cdr.getTotalCost());
    }

    @Cacheable(value = "ratePlans", key = "'default'")
    public RatePlan getDefaultRatePlan() {
        return ratePlanRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new RatePlanNotFoundException("No default rate plan configured"));
    }

    private Optional<RateCard> findBestRateCard(
            RatePlan plan,
            String metric,
            DestinationType destType,
            String destinationNumber
    ) {
        List<RateCard> cards = rateCardRepository.findByRatePlanIdAndMetric(plan.getId(), metric);

        Instant now = Instant.now();

        return cards.stream()
                .filter(rc -> isEffective(rc, now))
                .filter(rc -> matchesDestination(rc, destType, destinationNumber))
                .max(Comparator.comparing(RateCard::getPriority));
    }

    private boolean isEffective(RateCard rc, Instant now) {
        if (rc.getEffectiveFrom() != null && rc.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        if (rc.getEffectiveTo() != null && rc.getEffectiveTo().isBefore(now)) {
            return false;
        }
        return true;
    }

    private boolean matchesDestination(RateCard rc, DestinationType destType, String destinationNumber) {
        // Priority 1: Prefix match
        if (rc.getDestinationPrefix() != null && !rc.getDestinationPrefix().isEmpty()) {
            String normalizedDest = destinationNumber != null ? destinationNumber.replaceAll("[^0-9+]", "") : "";
            if (!normalizedDest.startsWith(rc.getDestinationPrefix())) {
                return false;
            }
        }

        // Priority 2: Destination type match
        if (rc.getDestinationType() != null) {
            return rc.getDestinationType() == destType;
        }

        // Fallback: metric-only match (no destination restriction)
        return true;
    }

    private BigDecimal calculateAiCost(RatePlan ratePlan, Cdr cdr) {
        BigDecimal sttCost = BigDecimal.ZERO;
        BigDecimal llmCost = BigDecimal.ZERO;

        // STT cost
        if (cdr.getAiSttSeconds() > 0) {
            Optional<RateCard> sttCard = findBestRateCard(ratePlan, "AI_STT_SECONDS", null, null);
            if (sttCard.isPresent()) {
                sttCost = sttCard.get().getRatePerUnit()
                        .multiply(BigDecimal.valueOf(cdr.getAiSttSeconds()))
                        .setScale(4, RoundingMode.HALF_UP);
            }
        }

        // LLM token cost
        if (cdr.getAiLlmTokens() > 0) {
            Optional<RateCard> llmCard = findBestRateCard(ratePlan, "AI_LLM_TOKENS", null, null);
            if (llmCard.isPresent()) {
                llmCost = llmCard.get().getRatePerUnit()
                        .multiply(BigDecimal.valueOf(cdr.getAiLlmTokens()))
                        .setScale(4, RoundingMode.HALF_UP);
            }
        }

        return sttCost.add(llmCost);
    }
}
