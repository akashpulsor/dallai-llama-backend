package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.leadmanagement.routing.RequestRoutingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Paid requests nobody took within the matching window go to Dalaillama's own account (the founder). */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingTimeoutJob {

    private final RequestRoutingStore store;
    private final BrandInquiryService inquiries;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${brands.request-matching.check-delay-ms:60000}",
            initialDelayString = "${brands.request-matching.check-delay-ms:60000}")
    public void run() {
        for (UUID id : store.matchingEnded(OffsetDateTime.now(clock))) {
            try {
                inquiries.keepForHouse(id);
            } catch (RuntimeException e) {
                log.error("Could not hand request {} to Dalaillama's account: {}", id, e.getMessage(), e);
            }
        }
    }
}
