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
 * opened payout and takes it from the wallet, then -- only when {@code razorpayx.payouts-enabled}
 * is on -- sends each prepared one to RazorpayX. While it is off, prepared payouts wait for ops to
 * pay them by hand and mark them paid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreatorPayoutDispatcher {

    private final CreatorPayoutService creatorPayoutService;

    @Value("${razorpayx.payouts-enabled:false}")
    private boolean automatic;

    @Scheduled(fixedDelayString = "${razorpayx.dispatch-interval-ms:60000}", initialDelay = 60_000)
    public void dispatch() {
        each(creatorPayoutService.toPrepare(), creatorPayoutService::prepare, "prepare");
        if (automatic) {
            each(creatorPayoutService.toSend(), creatorPayoutService::send, "send");
        }
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
