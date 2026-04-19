package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Marks PENDING payments past their expiry (15 min) as FAILED.
 * Runs every 2 minutes. Idempotent — only picks PENDING status.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailureChecker {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    @Scheduled(fixedRate = 120_000)
    @Transactional
    public void expireStalePayments() {
        List<Payment> expired = paymentRepository
                .findByStatusAndExpiredAtBefore(PaymentStatus.PENDING, Instant.now());

        if (expired.isEmpty()) return;

        log.info("Found {} expired PENDING payments", expired.size());

        for (Payment payment : expired) {
            try {
                 payment.markFailed(
                        "Payment expired - no capture within window"
                );
                paymentRepository.save(payment);

                paymentEventRepository.save(PaymentEvent.record(
                        payment, PaymentStatus.PENDING, PaymentStatus.FAILED,
                        "Payment expired", "SCHEDULER"
                ));

                log.info("Expired payment {} for tenant {}",
                        payment.getId(), payment.getTenantId());

            } catch (IllegalStateException e) {
                // Race condition: payment was captured between query and update
                log.warn("Skipping payment {} - already transitioned: {}",
                        payment.getId(), e.getMessage());
            }
        }
    }
}