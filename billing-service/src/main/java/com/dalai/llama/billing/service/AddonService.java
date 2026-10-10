package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One-off add-ons paid from the creator's wallet. Billing owns what each costs and how much it
 * grants; the caller passes a code and an idempotency key and gets back what was bought, so a
 * retried purchase never charges twice. */
@Slf4j
@Service
public class AddonService {

    public enum AddonCode { OUTREACH_MAIL_PACK }

    public record AddonOffer(AddonCode code, int quantity, BigDecimal price, String currency) {
    }

    public record AddonPurchase(AddonCode code, int quantity, BigDecimal price, String currency, BigDecimal balanceAfter) {
    }

    private final WalletService walletService;
    private final BillingStateService billingStateService;
    private final AddonOffer mailPack;

    public AddonService(WalletService walletService,
                        BillingStateService billingStateService,
                        @Value("${billing.addons.outreach-mail-pack.price-inr:499}") BigDecimal mailPackPrice,
                        @Value("${billing.addons.outreach-mail-pack.quantity:500}") int mailPackQuantity) {
        this.walletService = walletService;
        this.billingStateService = billingStateService;
        this.mailPack = new AddonOffer(AddonCode.OUTREACH_MAIL_PACK, mailPackQuantity, mailPackPrice, "INR");
    }

    public List<AddonOffer> catalog() {
        return List.of(mailPack);
    }

    /** Debits the wallet (402 via InsufficientBalanceException when it can't cover it). */
    @Transactional
    public AddonPurchase purchase(UUID tenantId, AddonCode code, String idempotencyKey) {
        AddonOffer offer = offer(code);
        walletService.debit(tenantId, offer.price(), TransactionType.ADDON, "ADDON:" + code + ":" + idempotencyKey,
                null, "addon:" + idempotencyKey, offer.quantity() + " outreach mails", null);
        billingStateService.evaluateState(tenantId);
        log.info("Addon purchased tenantId={} code={} key={}", tenantId, code, idempotencyKey);
        return new AddonPurchase(code, offer.quantity(), offer.price(), offer.currency(), walletService.getBalance(tenantId));
    }

    private AddonOffer offer(AddonCode code) {
        return catalog().stream().filter(o -> o.code() == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown add-on " + code));
    }
}
