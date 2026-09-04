package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.TransactionService;
import com.dalai.llama.billing.service.UsageService;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class UsageServiceImpl implements UsageService {

    private final UsageRecordRepository usageRecordRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final BillingStateService billingStateService;
    private final TransactionService transactionService;

    @Value("${billing.currency.conversion-rates:INR_INR=1,USD_INR=95}")
    private String conversionRatesConfig;

    @Value("${billing.creator-video-package.ai-short-starter-price-inr:5999}")
    private BigDecimal aiShortStarterPriceInr;

    @Override
    @Transactional
    public void createUsageFromCdr(Cdr cdr) {
        UsageRecord record = UsageRecord.fromCdr(cdr);
        usageRecordRepository.save(record);
    }

    @Override
    @Transactional
    public void recordBillableUsage(BillableUsageRequest request) {
        String idempotencyKey = effectiveIdempotencyKey(request);
        if (idempotencyKey != null && transactionService.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Skipping duplicate billable usage tenantId={} sourceType={} sourceId={} idempotencyKey={}",
                    request.tenantId(), request.sourceType(), request.sourceId(), idempotencyKey);
            return;
        }

        boolean packageUsage = isCreatorVideoPackageUsage(request);
        Wallet wallet = (packageUsage
                ? walletRepository.findByTenantIdForUpdate(request.tenantId())
                : walletRepository.findByTenantId(request.tenantId()))
                .orElseThrow(() -> new WalletNotFoundException(request.tenantId()));
        String tenantCurrency = normalizeCurrency(wallet.getCurrency());
        String sourceCurrency = normalizeCurrency(request.currency() == null ? tenantCurrency : request.currency());
        BigDecimal requestedBilledTotalCost = convertCurrency(defaultAmount(request.totalCost()), sourceCurrency, tenantCurrency, 4);
        BigDecimal billedTotalCost = packageUsage
                ? capCreatorVideoPackageCharge(request, requestedBilledTotalCost)
                : requestedBilledTotalCost;
        BigDecimal billedUnitCost = request.unitCost() == null
                ? null
                : convertCurrency(request.unitCost(), sourceCurrency, tenantCurrency, 6);
        if (packageUsage && billedUnitCost != null && defaultAmount(request.quantity()).signum() > 0) {
            billedUnitCost = billedTotalCost.divide(defaultAmount(request.quantity()), 6, RoundingMode.HALF_UP);
        }

        UsageRecord record = UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(request.tenantId())
                .projectId(request.projectId())
                .metric(request.metric())
                .quantity(defaultAmount(request.quantity()))
                .unit(request.unit())
                .unitCost(billedUnitCost)
                .totalCost(billedTotalCost)
                .sourceType(request.sourceType())
                .sourceId(request.sourceId())
                .description(request.description())
                .recordedAt(request.recordedAt() == null ? Instant.now() : request.recordedAt())
                .createdAt(Instant.now())
                .build();
        usageRecordRepository.save(record);

        if (record.getTotalCost().signum() > 0) {
            walletService.debit(
                    request.tenantId(),
                    record.getTotalCost(),
                    usageReference(request),
                    request.subscriptionId(),
                    idempotencyKey
            );
            log.info(
                    "Debited wallet for billable usage tenantId={} metric={} quantity={} sourceType={} sourceId={} sourceCurrency={} walletCurrency={} rawCost={} billedCost={} billingMarginPercent={} idempotencyKey={}",
                    request.tenantId(),
                    request.metric(),
                    record.getQuantity(),
                    request.sourceType(),
                    request.sourceId(),
                    sourceCurrency,
                    tenantCurrency,
                    defaultAmount(request.totalCost()).setScale(4, RoundingMode.HALF_UP),
                    record.getTotalCost(),
                    BigDecimal.ZERO,
                    idempotencyKey
            );
            billingStateService.evaluateState(request.tenantId());
        }
    }

    @Override
    public void trackProvisionedDid(Object event) {
        // Future: store DID reference for monthly rental billing
        // Intentionally empty for now
    }

    @Override
    public void untrackReleasedDid(Object event) {
        // Future: remove DID from billing scope
        // Intentionally empty for now
    }

    @Override
    public void chargeMonthlyDidRentals() {
        // Implemented in MonthlyDidRentalJob using Product Service client
    }

    private BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String effectiveIdempotencyKey(BillableUsageRequest request) {
        String requestedKey = request.idempotencyKey();
        if (requestedKey != null && !requestedKey.isBlank()) {
            return requestedKey.trim();
        }
        if ("CREATOR_VIDEO_PACKAGE".equalsIgnoreCase(request.sourceType()) && request.sourceId() != null) {
            return "CREATOR_VIDEO_PACKAGE:" + request.sourceId() + ":AI_SHORT_STARTER_60";
        }
        return null;
    }

    private boolean isCreatorVideoPackageUsage(BillableUsageRequest request) {
        return request != null
                && request.sourceId() != null
                && ("CREATOR_VIDEO_PACKAGE".equalsIgnoreCase(request.sourceType())
                || "CREATOR_VIDEO_PACKAGE_USAGE".equalsIgnoreCase(request.sourceType()));
    }

    private BigDecimal capCreatorVideoPackageCharge(BillableUsageRequest request, BigDecimal requestedAmountInr) {
        BigDecimal packagePrice = defaultAmount(aiShortStarterPriceInr).max(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        if (packagePrice.signum() <= 0) {
            return requestedAmountInr;
        }
        BigDecimal alreadyBilled = defaultAmount(usageRecordRepository.sumCostByPackageScope(
                request.tenantId(),
                request.sourceId(),
                List.of("CREATOR_VIDEO_PACKAGE_USAGE", "CREATOR_VIDEO_PACKAGE")
        )).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        BigDecimal remaining = packagePrice.subtract(alreadyBilled).max(BigDecimal.ZERO);
        BigDecimal capped = requestedAmountInr.max(BigDecimal.ZERO).min(remaining).setScale(4, RoundingMode.HALF_UP);
        log.info(
                "Creator video package cap tenantId={} packageScopeId={} requested={} alreadyBilled={} packagePrice={} walletDebit={}",
                request.tenantId(), request.sourceId(), requestedAmountInr, alreadyBilled, packagePrice, capped
        );
        return capped;
    }

    private BigDecimal convertCurrency(BigDecimal amount, String sourceCurrency, String targetCurrency, int scale) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        if (sourceCurrency.equals(targetCurrency)) {
            return amount.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal rate = conversionRates().get(sourceCurrency + "_" + targetCurrency);
        if (rate == null) {
            throw new IllegalArgumentException(
                    "No billing currency conversion rate configured for " + sourceCurrency + "_" + targetCurrency
            );
        }
        return amount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> conversionRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("INR_INR", BigDecimal.ONE);
        if (conversionRatesConfig == null || conversionRatesConfig.isBlank()) {
            return rates;
        }
        String[] entries = conversionRatesConfig.split("[,;]");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split("[:=]", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                continue;
            }
            rates.put(parts[0].trim().toUpperCase(Locale.ROOT), new BigDecimal(parts[1].trim()));
        }
        return rates;
    }

    private String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank()
                ? "INR"
                : currency.trim().toUpperCase(Locale.ROOT);
    }

    private String usageReference(BillableUsageRequest request) {
        String source = request.sourceType() == null || request.sourceType().isBlank()
                ? "UNKNOWN"
                : request.sourceType();
        return "USAGE:" + request.metric().name() + ":" + source;
    }
}
