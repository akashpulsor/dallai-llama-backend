package com.dalai.llama.product.scheduler;

import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import com.dalai.llama.product.repository.SipTrunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SipTrunkHealthChecker {

    private final SipTrunkRepository sipTrunkRepository;

    private static final int HEALTH_CHECK_TIMEOUT_MS = 5000;

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void checkHealth() {
        log.debug("Starting SIP trunk health check");

        List<SipTrunk> trunks = sipTrunkRepository.findByStatus(SipTrunkStatus.ACTIVE);
        int healthyCount = 0;
        int unhealthyCount = 0;

        for (SipTrunk trunk : trunks) {
            boolean isHealthy = performHealthCheck(trunk);

            if (isHealthy) {
                healthyCount++;
                if (!trunk.isHealthy()) {
                    log.info("SIP trunk {} recovered", trunk.getName());
                }
            } else {
                unhealthyCount++;
                if (trunk.isHealthy()) {
                    log.warn("SIP trunk {} became unhealthy", trunk.getName());
                }
            }

            trunk.setHealthy(isHealthy);
            trunk.setLastHealthCheck(Instant.now());
            trunk.setUpdatedAt(Instant.now());
        }

        sipTrunkRepository.saveAll(trunks);

        if (unhealthyCount > 0) {
            log.warn("SIP trunk health check completed: {} healthy, {} unhealthy",
                    healthyCount, unhealthyCount);
        } else {
            log.debug("SIP trunk health check completed: {} healthy", healthyCount);
        }
    }

    private boolean performHealthCheck(SipTrunk trunk) {
        // TCP connection check to SIP server
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(trunk.getServer(), trunk.getPort()),
                    HEALTH_CHECK_TIMEOUT_MS
            );
            return true;
        } catch (IOException e) {
            log.debug("Health check failed for trunk {}: {}", trunk.getName(), e.getMessage());
            return false;
        }
    }

    // Manual health check trigger
    public void checkTrunkHealth(SipTrunk trunk) {
        boolean isHealthy = performHealthCheck(trunk);
        trunk.setHealthy(isHealthy);
        trunk.setLastHealthCheck(Instant.now());
        trunk.setUpdatedAt(Instant.now());
        sipTrunkRepository.save(trunk);

        log.info("Manual health check for trunk {}: {}", trunk.getName(), isHealthy ? "healthy" : "unhealthy");
    }
}