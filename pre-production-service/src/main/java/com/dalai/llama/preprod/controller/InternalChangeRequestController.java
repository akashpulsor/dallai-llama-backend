package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ChangeRequestView;
import com.dalai.llama.preprod.dto.SuggestChangeRequestRequest;
import com.dalai.llama.preprod.service.ChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Called by chat-service's {@code SuggestPreProductionChangeActionExecutor}, service-to-service
 * -- same {@code /api/v1/internal/tenants/{tenantId}/...} permitAll shape as every other internal
 * controller in this service. */
@RestController
public class InternalChangeRequestController {

    private final ChangeRequestService changeRequestService;

    public InternalChangeRequestController(ChangeRequestService changeRequestService) {
        this.changeRequestService = changeRequestService;
    }

    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/change-requests")
    public ResponseEntity<ChangeRequestView> create(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, @Valid @RequestBody SuggestChangeRequestRequest request) {
        return ResponseEntity.ok(changeRequestService.create(tenantId, projectId, request));
    }
}
