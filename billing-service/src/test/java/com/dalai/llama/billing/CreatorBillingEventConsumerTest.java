package com.dalai.llama.billing;

import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.domain.event.CreatorAiUsageDebitEvent;
import com.dalai.llama.billing.kafka.consumer.CreatorBillingEventConsumer;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.UsageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CreatorBillingEventConsumerTest {

    @Test
    void recordsElevenLabsCharactersInsideProjectPackageScope() {
        UsageService usageService = mock(UsageService.class);
        CreatorBillingEventConsumer consumer = new CreatorBillingEventConsumer(usageService);
        UUID tenantId = UUID.randomUUID();
        UUID packageScopeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        consumer.consume(CreatorAiUsageDebitEvent.builder()
                .eventId(eventId)
                .tenantId(tenantId)
                .projectId(packageScopeId)
                .promptType("FOUNDER_VOICE_PREVIEW")
                .provider("elevenlabs")
                .model("eleven_v3")
                .rateUnit("CHARACTER_CREDIT")
                .amount(new BigDecimal("1.85"))
                .currency("INR")
                .occurredAt(OffsetDateTime.now())
                .costMetadata(Map.of(
                        "includedInPackage", true,
                        "packageScopeId", packageScopeId.toString(),
                        "usage", Map.of("providerReportedCharacters", 1240)
                ))
                .build());

        ArgumentCaptor<BillableUsageRequest> captor = ArgumentCaptor.forClass(BillableUsageRequest.class);
        verify(usageService).recordBillableUsage(captor.capture());
        BillableUsageRequest request = captor.getValue();
        assertThat(request.metric()).isEqualTo(UsageMetric.AI_AUDIO_CHARACTERS);
        assertThat(request.unit()).isEqualTo(BillingUnit.CHARACTER);
        assertThat(request.quantity()).isEqualByComparingTo("1240.0000");
        assertThat(request.sourceType()).isEqualTo("CREATOR_VIDEO_PACKAGE_USAGE");
        assertThat(request.sourceId()).isEqualTo(packageScopeId);
        assertThat(request.idempotencyKey()).isEqualTo(eventId.toString());
    }
}
