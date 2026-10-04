package com.dalai.llama.llmgateway.service.ratecard;

import com.dalai.llama.llmgateway.domain.entity.RateCard;
import com.dalai.llama.llmgateway.dto.RateCardView;
import com.dalai.llama.llmgateway.repository.RateCardRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateCardQueryServiceTest {

    private static final String SEEDANCE = "bytedance/seedance-2.0/fast";
    private final RateCardRepository repository = mock(RateCardRepository.class);
    private final RateCardQueryService service = new RateCardQueryService(repository);

    private static RateCard rate(String resolution, String perSecond, int daysAgo) {
        return RateCard.builder().modelId(SEEDANCE).resolution(resolution).perSecondCost(new BigDecimal(perSecond))
                .inputTokenCost(BigDecimal.ZERO).outputTokenCost(BigDecimal.ZERO).currency("USD")
                .effectiveFrom(OffsetDateTime.now().minusDays(daysAgo)).build();
    }

    @Test
    void theNewestRateOfEachTierIsReturned() {
        when(repository.findByModelIdAndEffectiveFromLessThanEqual(eq(SEEDANCE), any())).thenReturn(List.of(
                rate("480p", "0.0096", 30), rate("480p", "0.1076", 5), rate("720p", "0.24192", 5), rate(null, "0.24192", 5)));

        List<RateCardView> current = service.current(SEEDANCE);

        assertThat(current).hasSize(3);
        assertThat(current).filteredOn(r -> "480p".equals(r.resolution()))
                .singleElement().extracting(RateCardView::perSecondCost).isEqualTo(new BigDecimal("0.1076"));
    }
}
