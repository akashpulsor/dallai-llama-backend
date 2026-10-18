package com.dalai.llama.tenant.leadmanagement.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Mail sent as Dalai Llama itself (sign-in links, request alerts, brief links, digests), through
 * the same SMTP relay the creator sender uses. Never throws: a mail that can't be sent is logged
 * and reported as false, so a relay outage can't fail the request that triggered it. */
@Slf4j
@Service
public class PlatformMailer {

    private static final String DISPLAY_NAME = "Dalai Llama";

    private final JavaMailSender mailSender;
    private final Executor executor;
    private final String smtpUsername;
    private final String fromAddress;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformMailer(JavaMailSender mailSender,
                          @Value("${spring.mail.username:}") String smtpUsername,
                          @Value("${spring.mail.from:}") String fromAddress,
                          @Value("${spring.mail.enabled:true}") boolean enabled) {
        this(mailSender, sendThreads(), smtpUsername, fromAddress, enabled);
    }

    PlatformMailer(JavaMailSender mailSender, Executor executor, String smtpUsername, String fromAddress, boolean enabled) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.smtpUsername = smtpUsername;
        this.fromAddress = fromAddress;
        this.enabled = enabled;
    }

    public record Mail(String to, String subject, String text, String html, Map<String, String> headers) {
        public Mail(String to, String subject, String text, String html) {
            this(to, subject, text, html, Map.of());
        }
    }

    /** For mail a request triggers: sent off the request thread, once the request's transaction
     * has committed (never for one that rolled back), so a slow relay can't time the request out. */
    public void sendAfterCommit(Mail mail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendInBackground(mail);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendInBackground(mail);
            }
        });
    }

    /** Two threads are plenty for one relay; the queue absorbs a burst (a request offered to every
     * creator). Its own pool, not a Spring Executor bean, which would replace Boot's default one. */
    private static ExecutorService sendThreads() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1_000), task -> {
            Thread thread = new Thread(task, "platform-mail-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Lets queued mail go out on shutdown (up to 30 seconds). */
    @jakarta.annotation.PreDestroy
    void drain() throws InterruptedException {
        if (executor instanceof ExecutorService pool) {
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) log.warn("Platform mail still queued at shutdown was dropped");
        }
    }

    private void sendInBackground(Mail mail) {
        try {
            executor.execute(() -> send(mail));
        } catch (RejectedExecutionException e) {
            log.warn("Platform mail dropped (send queue full): to={} subject={}", mail.to(), mail.subject());
        }
    }

    /** Sends now and says whether it went; for callers that act on the result (the digest). */
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
