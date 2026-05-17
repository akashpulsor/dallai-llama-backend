package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.ProvisioningLog;
import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.dto.response.ProvisioningStatusResponse;
import com.dalai.llama.tenant.repository.ProvisioningLogRepository;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/apps")
public class ProvisioningController {

    private final TenantAppService tenantAppService;
    private final TenantService tenantService;
    private final ProvisioningTaskRepository taskRepository;
    private final ProvisioningLogRepository logRepository;
    @Qualifier("taskExecutor")
    private final Executor taskExecutor;

    // ════════════════════════════════════════════════════════════
    // POST /api/v1/tenants/apps/{tenantAppId}/provision
    //
    // Kicks off async provisioning. Returns 202 immediately.
    // UI subscribes to WS topic /topic/tenant/{tenantId}/provisioning
    // for real-time step progress.
    // ════════════════════════════════════════════════════════════

    @PostMapping("/{tenantAppId}/provision")
    public ResponseEntity<Map<String, Object>> provision(@PathVariable UUID tenantAppId) {
        log.info("Provision requested for TenantApp={}", tenantAppId);

        CompletableFuture.runAsync(() -> tenantAppService.provisionApp(tenantAppId), taskExecutor);

        return ResponseEntity.accepted().body(Map.of(
                "tenantAppId", tenantAppId,
                "status", "PROVISIONING",
                "message", "Provisioning started. Subscribe to WebSocket for progress."
        ));
    }

    // ════════════════════════════════════════════════════════════
    // GET /api/v1/tenants/apps/{tenantAppId}/provision/status
    //
    // Current provisioning task status (step, retries, error).
    // ════════════════════════════════════════════════════════════

    @GetMapping("/{tenantAppId}/provision/status")
    public ResponseEntity<ProvisioningStatusResponse> getStatus(@PathVariable UUID tenantAppId) {
        return taskRepository.findFirstByTenantAppIdOrderByStartedAtDesc(tenantAppId)
                .map(this::toStatusResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ════════════════════════════════════════════════════════════
    // GET /api/v1/tenants/apps/{tenantAppId}/provision/logs
    //
    // Full audit trail of every provisioning step attempt.
    // ════════════════════════════════════════════════════════════

    @GetMapping("/{tenantAppId}/provision/logs")
    public ResponseEntity<List<ProvisioningLogResponse>> getLogs(@PathVariable UUID tenantAppId) {
        List<ProvisioningLog> logs = logRepository.findByTenantAppIdOrderByStartedAtAsc(tenantAppId);
        List<ProvisioningLogResponse> response = logs.stream()
                .map(this::toLogResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════
    // DELETE APP
    // ════════════════════════════════════════════════════════════

    @DeleteMapping("/{appId}")
    public ResponseEntity<Void> deleteApp(
            @PathVariable UUID appId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    tenantAppService.deleteApp(appId, tenant.getId());
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ════════════════════════════════════════════════════════════
    // RETRY PROVISIONING
    // ════════════════════════════════════════════════════════════

    @PostMapping("/{appId}/retry")
    public ResponseEntity<Void> retryProvision(
            @PathVariable UUID appId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    CompletableFuture.runAsync(() -> tenantAppService.retryProvision(appId, tenant.getId()), taskExecutor);
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ════════════════════════════════════════════════════════════
    // INTERNAL DTOs & MAPPERS
    // ════════════════════════════════════════════════════════════

    private ProvisioningStatusResponse toStatusResponse(ProvisioningTask task) {
        return new ProvisioningStatusResponse(
                task.getStatus(),
                task.getCurrentStep(),
                task.getCurrentStepStatus(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getLastError()
        );
    }

    public record ProvisioningLogResponse(
            UUID id,
            String step,
            String status,
            int attemptNumber,
            String message,
            String errorDetail,
            long durationMs,
            String startedAt,
            String completedAt
    ) {}

    private ProvisioningLogResponse toLogResponse(ProvisioningLog log) {
        return new ProvisioningLogResponse(
                log.getId(),
                log.getStep().name(),
                log.getStatus().name(),
                log.getAttemptNumber(),
                log.getMessage(),
                log.getErrorDetail(),
                log.getDurationMs(),
                log.getStartedAt() != null ? log.getStartedAt().toString() : null,
                log.getCompletedAt() != null ? log.getCompletedAt().toString() : null
        );
    }
}
