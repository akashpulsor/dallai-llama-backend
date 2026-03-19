package com.dalai.llama.pbx.core.esl;



import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the ESL connection lifecycle:
 *   - Initial connect on application startup
 *   - Auto-reconnect with exponential backoff on disconnect
 *   - Health check (used by actuator health indicator)
 *   - Graceful shutdown
 *
 * Provides the shared EslClient instance to EslCommandExecutor and EslEventListener.
 *
 * FreeSWITCH runs on bare-metal (hostNetwork), so the ESL connection is over
 * plain TCP to the node's IP — NOT through Istio mesh.
 */
@Slf4j
@Component
public class EslConnectionManager {

    @Value("${freeswitch.esl.host:127.0.0.1}")
    private String eslHost;

    @Value("${freeswitch.esl.port:8021}")
    private int eslPort;

    @Value("${freeswitch.esl.password:ClueCon}")
    private String eslPassword;

    @Value("${freeswitch.esl.reconnect-delay-ms:5000}")
    private long reconnectDelayMs;

    @Value("${freeswitch.esl.connection-timeout-ms:10000}")
    private int connectionTimeoutMs;

    @Getter
    private final EslClient client = new EslClient();

    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "esl-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_BACKOFF_SECONDS = 60;

    @PostConstruct
    public void init() {
        connect();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ESL connection manager");
        reconnectScheduler.shutdownNow();
        client.disconnect();
    }

    /**
     * Attempt to connect. On failure, schedule reconnect with backoff.
     */
    public void connect() {
        try {
            client.connect(eslHost, eslPort, eslPassword, connectionTimeoutMs);
            reconnectAttempts.set(0);
            log.info("ESL connection established to {}:{}", eslHost, eslPort);
        } catch (IOException e) {
            log.error("ESL connection failed to {}:{} — {}", eslHost, eslPort, e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * Called when the event listener detects a broken connection.
     */
    public void onDisconnect() {
        log.warn("ESL connection lost — scheduling reconnect");
        scheduleReconnect();
    }

    /**
     * Health check for actuator /actuator/health.
     */
    public boolean isHealthy() {
        return client.isConnected();
    }

    /**
     * Schedule a reconnect attempt with exponential backoff.
     * Backoff: 5s, 10s, 20s, 40s, 60s (capped).
     */
    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        long delayMs = Math.min(
                reconnectDelayMs * (1L << Math.min(attempts - 1, 4)),
                MAX_BACKOFF_SECONDS * 1000L
        );

        log.info("ESL reconnect attempt #{} in {}ms", attempts, delayMs);

        reconnectScheduler.schedule(() -> {
            if (!client.isConnected()) {
                connect();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}