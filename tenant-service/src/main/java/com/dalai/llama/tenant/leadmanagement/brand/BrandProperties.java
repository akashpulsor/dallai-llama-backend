package com.dalai.llama.tenant.leadmanagement.brand;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Brand sign-in and brand-facing links, bound from {@code brands.*}. */
@ConfigurationProperties(prefix = "brands")
public record BrandProperties(
        /* HMAC key for brand session tokens, from a k8s secret. Blank = a random key per start,
         * which just means brands sign in again after a restart. */
        String sessionSecret,
        int sessionDays,
        int signInLinkMinutes,
        /* Sign-in links a brand can be sent per hour, so the form can't be used to mail-bomb. */
        int signInLinksPerHour,
        /* Public page the sign-in link opens, e.g. https://platform.dalaillama.in/brands/sign-in/ */
        String signInPageUrl,
        /* Brief page a converted request sends the brand to, e.g. https://creator.dalaillama.in/brief/ */
        String briefPageUrl,
        /* Creator-ui page listing requests, linked from the alert mail. */
        String creatorRequestsUrl
) {
}
