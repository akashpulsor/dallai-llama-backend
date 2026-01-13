package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import com.dalai.llama.product.domain.event.DidProvisionedEvent;
import com.dalai.llama.product.domain.event.DidReleasedEvent;
import com.dalai.llama.product.kafka.producer.ProductEventProducer;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.repository.SipTrunkRepository;
import com.dalai.llama.product.service.didww.DidwwProperties;
import com.dalai.llama.product.service.didww.dto.DidwwWebhookPayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/didww")
@RequiredArgsConstructor
@Tag(name = "DIDWW Webhooks", description = "DIDWW event notifications")
public class DidwwWebhookController {

    private final DidRepository didRepository;
    private final SipTrunkRepository sipTrunkRepository;
    private final ProductEventProducer eventProducer;
    private final DidwwProperties didwwProperties;

    @PostMapping
    @Operation(summary = "Handle DIDWW webhook", description = "Receives event notifications from DIDWW")
    public ResponseEntity<Void> handle(
            @RequestHeader(value = "X-DIDWW-Signature", required = false) String signature,
            @RequestBody String rawBody,
            @RequestBody DidwwWebhookPayload payload
    ) {
        log.info("Received DIDWW webhook: event={}, resourceType={}, resourceId={}",
                payload.getEvent(), payload.getResourceType(), payload.getResourceId());

        // 1. Verify signature
        if (!verifySignature(rawBody, signature)) {
            log.warn("Invalid DIDWW webhook signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 2. Process event
        try {
            switch (payload.getEvent()) {
                case "did.activated" -> handleDidActivated(payload);
                case "did.suspended" -> handleDidSuspended(payload);
                case "did.released" -> handleDidReleased(payload);
                case "did.provisioning_failed" -> handleProvisioningFailed(payload);
                case "trunk.status_changed" -> handleTrunkStatusChanged(payload);
                default -> log.debug("Unhandled DIDWW event: {}", payload.getEvent());
            }
        } catch (Exception e) {
            log.error("Error processing DIDWW webhook: {}", e.getMessage(), e);
            // Return 200 to prevent retries for processing errors
        }

        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            log.debug("No signature provided, skipping verification");
            return true; // Allow in dev mode
        }

        try {
            String secret = didwwProperties.getWebhookSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private void handleDidActivated(DidwwWebhookPayload payload) {
        log.info("DID activated: {}", payload.getResourceId());

        didRepository.findAll().stream()
                .filter(d -> payload.getResourceId().equals(d.getDidwwDidId()))
                .findFirst()
                .ifPresent(did -> {
                    did.setStatus(DidStatus.ACTIVE);
                    did.setProvisionedAt(Instant.now());
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);

                    eventProducer.publishDidProvisioned(DidProvisionedEvent.builder()
                            .tenantId(did.getTenantId())
                            .didId(did.getId())
                            .number(did.getNumber())
                            .provisionedAt(did.getProvisionedAt())
                            .occurredAt(Instant.now())
                            .build());

                    log.info("DID {} marked as ACTIVE", did.getNumber());
                });
    }

    private void handleDidSuspended(DidwwWebhookPayload payload) {
        log.info("DID suspended: {}", payload.getResourceId());

        didRepository.findAll().stream()
                .filter(d -> payload.getResourceId().equals(d.getDidwwDidId()))
                .findFirst()
                .ifPresent(did -> {
                    did.setStatus(DidStatus.SUSPENDED);
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);
                    log.info("DID {} marked as SUSPENDED", did.getNumber());
                });
    }

    private void handleDidReleased(DidwwWebhookPayload payload) {
        log.info("DID released: {}", payload.getResourceId());

        didRepository.findAll().stream()
                .filter(d -> payload.getResourceId().equals(d.getDidwwDidId()))
                .findFirst()
                .ifPresent(did -> {
                    did.setStatus(DidStatus.RELEASED);
                    did.setReleasedAt(Instant.now());
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);

                    eventProducer.publishDidReleased(DidReleasedEvent.builder()
                            .tenantId(did.getTenantId())
                            .didId(did.getId())
                            .number(did.getNumber())
                            .reason("DIDWW_RELEASED")
                            .releasedAt(did.getReleasedAt())
                            .occurredAt(Instant.now())
                            .build());

                    log.info("DID {} marked as RELEASED", did.getNumber());
                });
    }

    private void handleProvisioningFailed(DidwwWebhookPayload payload) {
        log.error("DID provisioning failed: {}", payload.getResourceId());

        didRepository.findAll().stream()
                .filter(d -> payload.getResourceId().equals(d.getDidwwDidId()))
                .findFirst()
                .ifPresent(did -> {
                    did.setStatus(DidStatus.PENDING);
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);
                    log.info("DID {} marked as PENDING due to provisioning failure", did.getNumber());
                });
    }

    private void handleTrunkStatusChanged(DidwwWebhookPayload payload) {
        log.info("Trunk status changed: {} -> {}", payload.getResourceId(), payload.getStatus());

        sipTrunkRepository.findByDidwwTrunkId(payload.getResourceId())
                .ifPresentOrElse(
                        trunk -> {
                            SipTrunkStatus newStatus = mapDidwwStatus(payload.getStatus());
                            trunk.setStatus(newStatus);
                            trunk.setHealthy(newStatus == SipTrunkStatus.ACTIVE);
                            trunk.setUpdatedAt(Instant.now());
                            sipTrunkRepository.save(trunk);
                            log.info("Updated SIP trunk {} status to {}", trunk.getName(), newStatus);
                        },
                        () -> log.warn("SIP trunk not found for DIDWW trunk ID: {}", payload.getResourceId())
                );
    }

    private SipTrunkStatus mapDidwwStatus(String didwwStatus) {
        if (didwwStatus == null) {
            return SipTrunkStatus.PENDING;
        }
        return switch (didwwStatus.toLowerCase()) {
            case "active", "enabled" -> SipTrunkStatus.ACTIVE;
            case "suspended", "disabled" -> SipTrunkStatus.SUSPENDED;
            case "failed", "error" -> SipTrunkStatus.FAILED;
            default -> SipTrunkStatus.PENDING;
        };
    }
}