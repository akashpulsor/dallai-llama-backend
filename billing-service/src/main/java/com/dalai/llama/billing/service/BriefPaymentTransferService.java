package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A brief handed from one creator account to another (a brand paid Dalaillama's brief, then a
 * creator took it): its payments follow it, so the creator's payout counts them, and the wallet
 * credit a gateway payment gave the first account moves to the second. A wallet-funded payment
 * credited nobody, so only its row moves. Safe to repeat: a payment already with the new owner is
 * left alone, and the wallet moves are keyed by payment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BriefPaymentTransferService {

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;

    @Transactional
    public void transfer(UUID fromTenantId, UUID requirementId, UUID toTenantId) {
        if (fromTenantId.equals(toTenantId)) return;
        for (Payment payment : paymentRepository.findByProjectRequirementIdOrderByCreatedAtDesc(requirementId)) {
            if (!fromTenantId.equals(payment.getTenantId())) continue;
            if (payment.getStatus() == PaymentStatus.SUCCESS && !"WALLET".equals(payment.getGateway())) {
                String reference = "BRIEF_TRANSFER:" + requirementId;
                walletService.debit(fromTenantId, payment.getAmount(), TransactionType.ADJUSTMENT_DEBIT, reference, null,
                        "BRIEF_TRANSFER_OUT:" + payment.getId() + ":" + toTenantId, "Brief handed to another creator", null);
                walletService.credit(toTenantId, payment.getAmount(), TransactionType.ADJUSTMENT_CREDIT, reference, null,
                        "BRIEF_TRANSFER_IN:" + payment.getId() + ":" + toTenantId, "Brief handed to you, already paid");
            }
            payment.handTo(toTenantId, walletService.getOrCreateWallet(toTenantId).getId());
            paymentRepository.save(payment);
            log.info("Payment {} of brief {} moved from tenant {} to {}", payment.getId(), requirementId, fromTenantId, toTenantId);
        }
    }
}
