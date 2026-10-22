package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.service.CreatorPayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Moves creator payouts along, one at a time, off the request path: works out the amount of each
 * opened payout, then sends each prepared one to RazorpayX. Off until {@code
 * razorpayx.payouts-enabled} is set, so nothing leaves the account before RazorpayX is set up --
 * payouts opened meanwhile just wait.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreatorPayoutDispatcher {

    private final CreatorPayoutService creatorPayoutService;

    @Value("${razorpayx.payouts-enabled:false}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${razorpayx.dispatch-interval-ms:60000}", initialDelay = 60_000)
    public void dispatch() {
        if (!enabled) {
            return;
        }
        each(creatorPayoutService.toPrepare(), creatorPayoutService::prepare, "prepare");
        each(creatorPayoutService.toSend(), creatorPayoutService::send, "send");
    }

    private static void each(Iterable<UUID> ids, Consumer<UUID> step, String name) {
        for (UUID id : ids) {
            try {
                step.accept(id);
            } catch (RuntimeException ex) {
                log.warn("Creator payout {} could not {}: {}", id, name, ex.getMessage(), ex);
            }
        }
    }
}
