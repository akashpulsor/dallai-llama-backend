package com.dalai.llama.tenant.leadmanagement.outreach;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outreach limits and links, bound from {@code outreach.*} (CREATOR_SHOWCASE.md rules 15–17). */
@ConfigurationProperties(prefix = "outreach")
public record OutreachProperties(
        /* Secret mixed into recipient hashes, from a k8s secret. Changing it forgets every
         * suppression and cooldown, so set it once. */
        String hashPepper,
        int freePerWeek,
        int maxRecipientsPerSend,
        int cooldownDays,
        int digestMaxCards,
        /* A queued mail not delivered within this many days is dropped. */
        int pendingExpiryDays,
        int retentionDays,
        /* Public base of the tracked-link and unsubscribe endpoints, e.g.
         * https://api.dalaillama.in/api/v1/public/outreach */
        String publicApiBaseUrl
) {
}
