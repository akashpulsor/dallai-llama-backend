package com.dalai.llama.tenant.leadmanagement.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Mail sent as Dalai Llama itself (sign-in links, request alerts, brief links, digests), through
 * the same SMTP relay the creator sender uses. Never throws: a mail that can't be sent is logged
 * and reported as false, so a relay outage can't fail the request that triggered it. */
@Slf4j
@Service
public class PlatformMailer {

    private static final String DISPLAY_NAME = "Dalai Llama";

    private final JavaMailSender mailSender;
    private final String smtpUsername;
    private final String fromAddress;
    private final boolean enabled;

    public PlatformMailer(JavaMailSender mailSender,
                          @Value("${spring.mail.username:}") String smtpUsername,
                          @Value("${spring.mail.from:}") String fromAddress,
                          @Value("${spring.mail.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.smtpUsername = smtpUsername;
        this.fromAddress = fromAddress;
        this.enabled = enabled;
    }

    public record Mail(String to, String subject, String text, String html, Map<String, String> headers) {
        public Mail(String to, String subject, String text, String html) {
            this(to, subject, text, html, Map.of());
        }
    }

    public boolean send(Mail mail) {
        if (!enabled || smtpUsername == null || smtpUsername.isBlank()) {
            log.warn("Platform mail not sent (SMTP not configured): to={} subject={}", mail.to(), mail.subject());
            return false;
        }
        try {
            String from = fromAddress == null || fromAddress.isBlank() ? smtpUsername : fromAddress;
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, DISPLAY_NAME, StandardCharsets.UTF_8.name()));
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            if (mail.html() != null) helper.setText(mail.text(), mail.html());
            else helper.setText(mail.text(), false);
            for (Map.Entry<String, String> header : mail.headers().entrySet()) mime.setHeader(header.getKey(), header.getValue());
            mailSender.send(mime);
            return true;
        } catch (Exception e) {
            log.warn("Platform mail failed to={} subject={}: {}", mail.to(), mail.subject(), e.getMessage());
            return false;
        }
    }
}
