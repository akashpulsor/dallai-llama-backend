package com.dalai.llama.billing;

import com.dalai.llama.billing.domain.event.LlmBillingEvent;
import com.dalai.llama.billing.kafka.consumer.LlmBillingEventConsumer;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.LlmUsageMargin;
import com.dalai.llama.billing.service.UsageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmBillingEventConsumerTest {

    @Test
    void debitsTheWalletRawCostPlusTwentyPercent() {
        UsageService usageService = mock(UsageService.class);
        LlmBillingEventConsumer consumer = new LlmBillingEventConsumer(usageService, new LlmUsageMargin(new BigDecimal("20")));
        LlmBillingEvent event = new LlmBillingEvent();
        event.setEventId(UUID.randomUUID());
        event.setJobId(UUID.randomUUID());
        event.setTenantId(UUID.randomUUID().toString());
        event.setCost(new BigDecimal("0.5000"));
        event.setInputTokens(1000);
        event.setOutputTokens(500);
        event.setCurrency("USD");

        consumer.consume(event);

        ArgumentCaptor<BillableUsageRequest> captor = ArgumentCaptor.forClass(BillableUsageRequest.class);
        verify(usageService).recordBillableUsage(captor.capture());
        assertThat(captor.getValue().totalCost()).isEqualByComparingTo("0.6000");
    }
}
