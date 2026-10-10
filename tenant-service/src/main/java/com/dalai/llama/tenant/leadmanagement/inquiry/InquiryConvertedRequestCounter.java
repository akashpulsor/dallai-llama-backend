package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.ranking.ConvertedRequestCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Brand requests a creator turned into briefs: the L4 signal the ranking reads. */
@Component
@RequiredArgsConstructor
public class InquiryConvertedRequestCounter implements ConvertedRequestCounter {

    private final BrandInquiryRepository inquiryRepository;

    @Override
    public int convertedRequests(UUID tenantId) {
        return (int) inquiryRepository.countByTenantIdAndStatus(tenantId, BrandInquiry.Status.CONVERTED);
    }
}
