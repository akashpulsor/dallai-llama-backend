package com.dalai.llama.tenant.leadmanagement.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Matching a paid request to a creator, bound from {@code brands.request-matching}. */
@ConfigurationProperties(prefix = "brands.request-matching")
public record RequestMatchingProperties(
        /* The account (its sign-in or contact email) of Dalaillama's own creator, the founder, who
         * holds requests until a creator takes them and makes the ones nobody takes. Blank: requests
         * wait unassigned for a creator before the brand can pay, as before. */
        String houseCreatorEmail,
        /* How long a paid request is offered to creators before the founder keeps it. */
        Duration window
) {
    public RequestMatchingProperties {
        if (window == null) window = Duration.ofHours(2);
    }
}
