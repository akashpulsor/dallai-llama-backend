package com.dalai.llama.tenant.showcase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Creator Showcase settings, bound from {@code showcase.*}. Every threshold the design says is
 * tunable lives here, never as a literal in a service. */
@ConfigurationProperties(prefix = "showcase")
public record ShowcaseProperties(
        /* Public site origin used to build profile links, e.g. https://dalaillama.in. */
        String publicBaseUrl,
        /* Cached YouTube data older than this is never served publicly (YouTube's limit is 30). */
        int publicDataMaxAgeDays,
        Profile profile,
        Picks picks,
        Funding funding,
        PlatformMatch platformMatch
) {

    public record Profile(
            /* Days a creator must wait between handle changes. */
            int handleChangeCooldownDays,
            /* Days a released handle stays reserved for its old owner. */
            int oldHandleRedirectDays,
            /* Most industries a creator can list. */
            int maxIndustries,
            /* Handles nobody may claim because they collide with site routes or the brand. */
            List<String> reservedHandles
    ) {
    }

    public record Picks(
            /* Videos a creator must pick from their channel before their profile is shown. */
            int minInitialPicks,
            /* Most videos from their own channel a creator can show, so they show their best. */
            int maxExternalPicks
    ) {
    }

    /** The evidence gate that turns a client-locked project into "verified funded" (rule 8). */
    public record Funding(
            boolean requireClientLocked,
            /* Images generated for the project's shots. */
            long minShotImages,
            long minClientReviewSessions,
            /* Client review comments plus client approvals of a creative direction. */
            long minClientCommentsOrApprovals
    ) {
    }

    /** How a creator-uploaded YouTube link is matched to a project's film (rule 4). */
    public record PlatformMatch(
            double durationToleranceSeconds,
            /* First words of the line our upload kit puts in the description. */
            String markerPrefix
    ) {
    }
}
