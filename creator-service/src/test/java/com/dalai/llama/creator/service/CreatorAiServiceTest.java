package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.event.CreatorAiUsageDebitEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatorAiServiceTest {

    @Test
    void publishesProviderDebitImmediatelyWithStableIdInsideTransaction() {
        CreatorProperties properties = new CreatorProperties();
        properties.getAi().getBilling().setEnabled(true);
        properties.getAi().getBilling().setVideoUsageMarkupPercent(new BigDecimal("20"));
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        FalProviderBillingService falProviderBillingService = mock(FalProviderBillingService.class);
        when(falProviderBillingService.resolve(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        CreatorAiService service = new CreatorAiService(
                List.of(),
                mock(CreatorAiProviderCatalogService.class),
                properties,
                new ObjectMapper(),
                kafkaTemplate,
                mock(CreatorAiPricingService.class),
                mock(BillingWalletService.class),
                falProviderBillingService
        );
        UUID tenantId = UUID.randomUUID();
        UUID generationJobId = UUID.randomUUID();
        CreatorAiService.AiUsageContext context = new CreatorAiService.AiUsageContext(
                tenantId.toString(),
                "user-1",
                UUID.randomUUID(),
                generationJobId,
                null
        );
        Map<String, Object> cost = Map.of(
                "modelApiInteracted", true,
                "actualTotalCost", new BigDecimal("0.42"),
                "currency", "USD",
                "usage", Map.of("sceneId", "scene-1")
        );

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.publishProviderUsageDebit(
                    "SCREENPLAY_VIDEO_SCENE",
                    "fal.ai",
                    "fal-ai/heygen/avatar4/image-to-video",
                    cost,
                    context,
                    "Avatar scene"
            );
            service.publishProviderUsageDebit(
                    "SCREENPLAY_VIDEO_SCENE",
                    "fal.ai",
                    "fal-ai/heygen/avatar4/image-to-video",
                    cost,
                    context,
                    "Avatar scene retry"
            );

            var eventCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(kafkaTemplate, times(2)).send(
                    eq(properties.getKafka().getBillingEventsTopic()),
                    eq(tenantId.toString()),
                    eventCaptor.capture()
            );
            List<CreatorAiUsageDebitEvent> events = eventCaptor.getAllValues().stream()
                    .map(CreatorAiUsageDebitEvent.class::cast)
                    .toList();
            assertThat(events.get(0).getEventId()).isEqualTo(events.get(1).getEventId());
            assertThat(events.get(0).getAmount()).isEqualByComparingTo("0.504000");
            assertThat(events.get(0).getCostMetadata())
                    .containsEntry("actualTotalCost", new BigDecimal("0.42"))
                    .containsEntry("billingMarkupPercent", new BigDecimal("20"));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
