package com.dalai.llama.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Bare-bones email sender fronting Spring's JavaMailSender. Hostinger's shared hosting includes
 * SMTP on smtp.hostinger.com:465 SSL; we push through one of the mailboxes the account already
 * owns (see application.yml spring.mail block). Rate cap on Hostinger is ~300 messages/hour per
 * mailbox -- fine for one client's payment receipts + occasional operational notes; anything
 * that needs bulk send (partner-domain campaigns, marketing digests) should route through Amazon
 * SES's 62k/month free tier from an EC2 host instead.
 *
 * <p>Deliberately narrow surface for now: one text-only {@code send} method + an
 * {@code enabled} flag so a deployment without SMTP creds no-ops rather than crashing the app.
 * HTML templates + Thymeleaf come when we have a second call site to justify the abstraction
 * (right now the only intended caller is a payment-receipt path we haven't hooked up yet).
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final boolean enabled;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.from:}") String fromAddress,
            @Value("${spring.mail.enabled:true}") boolean enabled
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.enabled = enabled;
    }

    public void send(String to, String subject, String body) {
        if (!enabled) {
            log.info("[email disabled] would send to={} subject={}", to, subject);
            return;
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("Email skipped -- spring.mail.from not configured; message={} to={}", subject, to);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Sent email to={} subject={}", to, subject);
        } catch (Exception ex) {
            // Never let a failed email break the request path -- payment/webhook consumers that
            // trigger notification sends have no business rolling back for a transient SMTP hiccup.
            log.warn("Failed to send email to={} subject={} cause={}", to, subject, ex.getMessage(), ex);
        }
    }
}
