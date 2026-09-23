package com.dalai.llama.tenant.leadmanagement.email;

import lombok.Builder;

import java.util.List;

/** Outbound email payload the {@link CreatorEmailSender} accepts. Kept minimal on purpose: the
 * concrete outbound provider (SES / SendGrid / Postmark / etc.) has not been chosen yet, so
 * exposing provider-specific knobs on this record would prematurely lock in an abstraction.
 *
 * <p>{@code fromCreatorId} is the tenantId whose creator identity should be used as the sender
 * -- the sender resolves the address via {@link
 * com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService} rather than
 * accepting a raw From address, so a caller cannot spoof another creator. */
@Builder
public record CreatorEmailMessage(
        java.util.UUID fromCreatorId,
        List<String> to,
        String subject,
        String bodyText,
        String bodyHtml,
        String replyTo
) {
}
