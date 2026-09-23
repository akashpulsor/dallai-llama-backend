package com.dalai.llama.tenant.leadmanagement.email;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** SMTP-relay implementation of {@link CreatorEmailSender}. Sends via the same Hostinger
 * relay {@code billing-service}'s EmailService uses (single shared account authenticated
 * with {@code SMTP_USERNAME}/{@code SMTP_PASSWORD}). Recipient-facing sender is set as
 * {@code "Display Name" <cr_<uuid>@partner.dalaillama.in>} so a Gmail recipient sees the
 * creator's display name and the ugly identity string stays hidden behind a header expand.
 *
 * <p>Hostinger's SMTP AUTH requires the {@code MAIL FROM} envelope address to be a mailbox
 * on the authenticated account -- our virtual {@code cr_*@partner.dalaillama.in} is NOT such
 * a mailbox. So we set the envelope From (aka {@code Return-Path}) to {@code SMTP_FROM} (the
 * shared account) and set the HEADER From to the creator's identity string. This is the
 * standard "send-on-behalf-of" pattern; recipient's mail client displays the header, and
 * bounces come back to the shared account.
 *
 * <p>{@code Reply-To} is also set to the creator identity so recipient replies go through
 * Cloudflare Email Routing back to the platform, not to the shared Hostinger inbox.
 *
 * <p>No-ops with an explicit rejected result when SMTP creds aren't configured -- the caller
 * (self-service send endpoint) turns that into a 503 with a clear message rather than a
 * silent success. */
@Slf4j
@Service
public class SmtpCreatorEmailSender implements CreatorEmailSender {

    private final JavaMailSender mailSender;
    private final CreatorEmailIdentityService identityService;
    private final String smtpUsername;
    private final String envelopeFrom;
    private final boolean enabled;

    public SmtpCreatorEmailSender(
            JavaMailSender mailSender,
            CreatorEmailIdentityService identityService,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${spring.mail.from:}") String envelopeFrom,
            @Value("${spring.mail.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.identityService = identityService;
        this.smtpUsername = smtpUsername;
        this.envelopeFrom = envelopeFrom;
        this.enabled = enabled;
    }

    @Override
    public EmailSendResult send(CreatorEmailMessage message) {
        if (!enabled || smtpUsername == null || smtpUsername.isBlank()) {
            return EmailSendResult.rejected("SMTP relay not configured (SMTP_USERNAME empty)");
        }
        if (message.to() == null || message.to().isEmpty()) {
            return EmailSendResult.rejected("No recipients");
        }
        UUID creatorId = message.fromCreatorId();
        if (creatorId == null) {
            return EmailSendResult.rejected("fromCreatorId is required");
        }
        CreatorEmailIdentity identity = identityService.findByTenant(creatorId).orElse(null);
        if (identity == null) {
            return EmailSendResult.rejected("No creator email identity for tenant " + creatorId);
        }

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            // Envelope MAIL FROM (Return-Path) -- required by Hostinger to be a real mailbox on
            // the authenticated account or the relay rejects with 553. Set via the standard
            // {@code Return-Path} header; JavaMailSender uses it as the SMTP envelope From when
            // {@code mail.smtp.from} isn't explicitly configured. Fall back to smtpUsername.
            String envelope = (envelopeFrom == null || envelopeFrom.isBlank()) ? smtpUsername : envelopeFrom;
            mime.setHeader("Return-Path", "<" + envelope + ">");
            mime.setHeader("Sender", envelope);
            // HEADER From -- what the recipient's mail client displays. Display-name aliasing
            // happens here (see class doc).
            String displayName = identity.getDisplayName();
            InternetAddress headerFrom = (displayName == null || displayName.isBlank())
                    ? new InternetAddress(identity.getEmail())
                    : new InternetAddress(identity.getEmail(), displayName, StandardCharsets.UTF_8.name());
            helper.setFrom(headerFrom);
            // Route recipient replies back to the creator identity so Cloudflare Routing
            // catches them, not the shared Hostinger inbox.
            helper.setReplyTo(message.replyTo() != null && !message.replyTo().isBlank()
                    ? message.replyTo()
                    : identity.getEmail());
            helper.setTo(message.to().toArray(new String[0]));
            helper.setSubject(message.subject() == null ? "" : message.subject());
            boolean hasHtml = message.bodyHtml() != null && !message.bodyHtml().isBlank();
            String textBody = message.bodyText() == null ? "" : message.bodyText();
            if (hasHtml) {
                // Use setText(plain, html) -- MimeMessageHelper builds the multipart/alternative
                // itself. Passing an empty plain part is fine; some clients will still show the
                // HTML.
                helper.setText(textBody, message.bodyHtml());
            } else {
                helper.setText(textBody, false);
            }
            mailSender.send(mime);
            log.info("Sent creator email tenantId={} from={} to={} subject={}",
                    creatorId, identity.getEmail(), message.to(), message.subject());
            // No provider-side message id from JavaMailSender; leave null.
            return EmailSendResult.accepted(null);
        } catch (Exception ex) {
            log.warn("Failed to send creator email tenantId={} to={} cause={}",
                    creatorId, message.to(), ex.getMessage(), ex);
            return EmailSendResult.rejected(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
