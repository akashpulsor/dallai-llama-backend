package com.dalai.llama.tenant.leadmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Namespaced config for the lead-management module. Bound from {@code lead-management.*} in
 * application.yml. The {@code inboundWebhookSecret} field must be sourced from a
 * Kubernetes Secret via env var -- never checked in, never returned via API. */
@ConfigurationProperties(prefix = "lead-management")
public record LeadManagementProperties(
        /* The dedicated creator email namespace. All creator identities are minted as
         * cr_<tenantId>@<domain>; see CreatorEmailIdentityService for the local-part format. */
        String domain,

        /* Shared secret the inbound Cloudflare Worker must present in the X-Webhook-Secret
         * header for /api/v1/internal/lead-management/email/inbound. Compared with a
         * constant-time equals. Blank/null disables the endpoint (returns 503) rather than
         * accepting unauthenticated traffic -- safer default when the secret is missing. */
        String inboundWebhookSecret,

        /* Maximum inbound-webhook body size in bytes. Cloudflare Email Routing hands the raw
         * MIME message straight through, so a very large attachment can arrive here even
         * though Phase 1 does not parse it. Anything larger than this is rejected with 413. */
        long inboundWebhookMaxBodyBytes,

        /* Replay-protection window. The Worker sends an X-Webhook-Timestamp (epoch seconds)
         * with each call; requests whose timestamp differs from the server clock by more than
         * this many seconds are rejected with 401. */
        long inboundWebhookTimestampToleranceSeconds
) {
}
