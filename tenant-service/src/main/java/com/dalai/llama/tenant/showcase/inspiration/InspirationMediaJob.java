package com.dalai.llama.tenant.showcase.inspiration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fills each inspiration item's picture, video file and maker from its pin's info, a batch an hour
 * (and soon after start), so the board and the landing page play it in our own player. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspirationMediaJob {

    private static final int BATCH = 50;

    private final InspirationStore store;
    private final InspirationPosters posters;

    @Scheduled(fixedDelayString = "${showcase.inspiration.media-delay-ms:3600000}", initialDelayString = "${showcase.inspiration.media-initial-delay-ms:60000}")
    public void scheduled() {
        int filled = run();
        if (filled > 0) log.info("Inspiration media filled for {} items", filled);
    }

    public int run() {
        int filled = 0;
        for (InspirationItem item : store.withoutMedia(BATCH)) {
            var poster = posters.fetch(item.pinId());
            if (poster.isEmpty()) continue;
            store.setPoster(item.id(), poster.get());
            filled++;
        }
        return filled;
    }
}
