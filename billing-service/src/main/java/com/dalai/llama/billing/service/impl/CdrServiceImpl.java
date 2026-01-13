package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.CdrRepository;
import com.dalai.llama.billing.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CdrServiceImpl implements CdrService {

    private final CdrRepository cdrRepository;
    private final RatingService ratingService;
    private final UsageService usageService;
    private final WalletService walletService;
    private final BillingStateService billingStateService;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public void processCompletedCdr(Object event) {
        Cdr cdr = Cdr.fromEvent(event);

        if (cdrRepository.existsByCallIdAndTenantId(cdr.getCallId(), cdr.getTenantId())) {
            return;
        }

        ratingService.rate(cdr);
        cdrRepository.save(cdr);

        usageService.createUsageFromCdr(cdr);
        walletService.debit(cdr.getTenantId(), cdr.getTotalCost(), "CDR:" + cdr.getCallId());
        billingStateService.evaluateState(cdr.getTenantId());

        eventProducer.publishCdrRated(cdr.toRatedEvent());
    }
}
