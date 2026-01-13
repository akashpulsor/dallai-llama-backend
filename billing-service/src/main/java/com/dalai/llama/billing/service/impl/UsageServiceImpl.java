package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UsageServiceImpl implements UsageService {

    private final UsageRecordRepository usageRecordRepository;

    @Override
    @Transactional
    public void createUsageFromCdr(Cdr cdr) {
        UsageRecord record = UsageRecord.fromCdr(cdr);
        usageRecordRepository.save(record);
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
}
