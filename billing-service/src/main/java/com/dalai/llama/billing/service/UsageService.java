package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Cdr;

public interface UsageService {

    void createUsageFromCdr(Cdr cdr);

    void recordBillableUsage(BillableUsageRequest request);

    void trackProvisionedDid(Object event);

    void untrackReleasedDid(Object event);

    void chargeMonthlyDidRentals();
}
