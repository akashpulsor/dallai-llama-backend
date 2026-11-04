package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.outreach.OpenAnalytics;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

/** Links inside ops mail (TrackedLinks), public under the outreach prefix {@code /api/v1/public/outreach}.
 * A link only ever goes to the address the mail was sent with, never one given in the request. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/outreach/ops")
public class OpsMailTrackingController {

    private static final byte[] PIXEL = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final OpsMailHistoryStore store;
    private final OpenAnalytics analytics;
    private final ShowcaseProperties showcase;
    private final Clock clock;

    /** Counts the click, then on to the link; an unknown link goes to the site. */
    @GetMapping("/c/{deliveryId}/{position}")
    public ResponseEntity<Void> click(@PathVariable UUID deliveryId, @PathVariable int position) {
        String target = store.click(deliveryId, position, clock.instant()).orElse(showcase.publicBaseUrl());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /** The open pixel in a designed ops mail; always the same image, never cached. Each open is also a
     * Google Analytics event (OpenAnalytics#opsOpened). */
    @GetMapping(value = "/o/{deliveryId}.gif", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> open(@PathVariable UUID deliveryId) {
        store.open(deliveryId, clock.instant())
                .ifPresent(o -> analytics.opsOpened(o.mailFor(), o.recipientKey(), o.templateName(), o.firstOpen()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PIXEL.clone());
    }
}
