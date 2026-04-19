package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.dto.response.PaymentEventResponse;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/billing/{tenantId}/activity")
@RequiredArgsConstructor
@Tag(name = "Activity", description = "User activity and payment journey APIs")
public class ActivityController {

    private final TransactionRepository transactionRepository;
    private final PaymentEventRepository paymentEventRepository;

    /**
     * GET /api/v1/billing/{tenantId}/activity
     * Unified timeline: wallet transactions + payment state changes, sorted newest first.
     */
    @GetMapping
    @Operation(summary = "Get activity timeline",
            description = "Merged timeline of transactions and payment events")
    public ResponseEntity<List<ActivityItem>> getActivity(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {

        List<Transaction> txns = transactionRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId);

        List<PaymentEvent> events = paymentEventRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId);

        Stream<ActivityItem> txnStream = txns.stream()
                .map(tx -> ActivityItem.builder()
                        .id(tx.getId())
                        .type("TRANSACTION")
                        .subType(tx.getType().name())
                        .amount(tx.getAmount())
                        .description(tx.getDescription())
                        .reference(tx.getReference())
                        .timestamp(tx.getCreatedAt())
                        .build());

        Stream<ActivityItem> eventStream = events.stream()
                .map(ev -> ActivityItem.builder()
                        .id(ev.getId())
                        .type("PAYMENT_EVENT")
                        .subType(ev.getToStatus().name())
                        .description(ev.getReason())
                        .reference(ev.getGatewayOrderId())
                        .metadata(Map.of(
                                "fromStatus", ev.getFromStatus() != null
                                        ? ev.getFromStatus().name() : "NEW",
                                "toStatus", ev.getToStatus().name(),
                                "source", ev.getSource() != null ? ev.getSource() : ""
                        ))
                        .timestamp(ev.getCreatedAt())
                        .build());

        List<ActivityItem> merged = Stream.concat(txnStream, eventStream)
                .sorted(Comparator.comparing(ActivityItem::getTimestamp).reversed())
                .limit(limit)
                .toList();

        return ResponseEntity.ok(merged);
    }

    /**
     * GET /api/v1/tenants/{tenantId}/activity/payments/{paymentId}/journey
     * Full state machine history for a single payment.
     */
    @GetMapping("/payments/{paymentId}/journey")
    @Operation(summary = "Get payment journey",
            description = "Complete state transition history for a payment")
    public ResponseEntity<List<PaymentEventResponse>> getPaymentJourney(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId) {

        List<PaymentEvent> events = paymentEventRepository
                .findByPaymentIdOrderByCreatedAtAsc(paymentId);

        // Verify tenant ownership
        if (!events.isEmpty() && !events.get(0).getTenantId().equals(tenantId)) {
            return ResponseEntity.notFound().build();
        }

        List<PaymentEventResponse> responses = events.stream()
                .map(ev -> PaymentEventResponse.builder()
                        .id(ev.getId())
                        .paymentId(ev.getPaymentId())
                        .fromStatus(ev.getFromStatus() != null
                                ? ev.getFromStatus().name() : null)
                        .toStatus(ev.getToStatus().name())
                        .gatewayOrderId(ev.getGatewayOrderId())
                        .gatewayPaymentId(ev.getGatewayPaymentId())
                        .reason(ev.getReason())
                        .source(ev.getSource())
                        .createdAt(ev.getCreatedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(responses);
    }

    @Builder
    @Getter
    public static class ActivityItem {
        private UUID id;
        private String type;       // TRANSACTION | PAYMENT_EVENT
        private String subType;    // RECHARGE, SUCCESS, FAILED, etc.
        private BigDecimal amount;
        private String description;
        private String reference;
        private Map<String, String> metadata;
        private Instant timestamp;
    }
}