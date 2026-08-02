package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.CreateHumanWorkOrderRequest;
import com.dalai.llama.creator.dto.request.HumanWorkOrderMessageRequest;
import com.dalai.llama.creator.dto.request.UpdateHumanWorkOrderRequest;
import com.dalai.llama.creator.dto.request.UpdateHumanWorkerPresenceRequest;
import com.dalai.llama.creator.dto.response.HumanWorkOrderResponse;
import com.dalai.llama.creator.dto.response.HumanWorkerResponse;
import com.dalai.llama.creator.service.CreatorHumanWorkOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/human-work-orders")
public class CreatorHumanWorkOrderController {

    private final CreatorHumanWorkOrderService workOrderService;

    public CreatorHumanWorkOrderController(CreatorHumanWorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @PostMapping
    public ResponseEntity<HumanWorkOrderResponse> submit(
            @Valid @RequestBody(required = false) CreateHumanWorkOrderRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(workOrderService.submit(request, tenantId, userId));
    }

    @GetMapping
    public ResponseEntity<List<HumanWorkOrderResponse>> listMine(
            @RequestParam(required = false) UUID scriptId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workOrderService.listMine(tenantId, userId, scriptId, limit));
    }

    @GetMapping("/{workOrderId}")
    public ResponseEntity<HumanWorkOrderResponse> getMine(
            @PathVariable UUID workOrderId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workOrderService.getMine(workOrderId, tenantId, userId));
    }

    @PostMapping("/{workOrderId}/messages")
    public ResponseEntity<HumanWorkOrderResponse> addMessage(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody HumanWorkOrderMessageRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workOrderService.addCreatorMessage(workOrderId, request, tenantId, userId));
    }

    @PostMapping("/{workOrderId}/request-changes")
    public ResponseEntity<HumanWorkOrderResponse> requestChanges(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody HumanWorkOrderMessageRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workOrderService.requestChanges(workOrderId, request, tenantId, userId));
    }

    @PostMapping("/{workOrderId}/approve")
    public ResponseEntity<HumanWorkOrderResponse> approve(
            @PathVariable UUID workOrderId,
            @RequestBody(required = false) UpdateHumanWorkOrderRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workOrderService.approve(workOrderId, request, tenantId, userId));
    }

    @GetMapping("/queue")
    public ResponseEntity<List<HumanWorkOrderResponse>> queue(
            @RequestParam(required = false) String workType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication
    ) {
        return ResponseEntity.ok(workOrderService.queue(workType, status, limit, workOrderService.workerContext(authentication)));
    }

    @GetMapping("/queue/{workOrderId}")
    public ResponseEntity<HumanWorkOrderResponse> getForWorker(
            @PathVariable UUID workOrderId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(workOrderService.getForWorker(workOrderId, workOrderService.workerContext(authentication)));
    }

    @PatchMapping("/queue/{workOrderId}")
    public ResponseEntity<HumanWorkOrderResponse> updateForWorker(
            @PathVariable UUID workOrderId,
            @RequestBody(required = false) UpdateHumanWorkOrderRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(workOrderService.updateForWorker(workOrderId, request, workOrderService.workerContext(authentication)));
    }

    @PostMapping("/queue/{workOrderId}/messages")
    public ResponseEntity<HumanWorkOrderResponse> addWorkerMessage(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody HumanWorkOrderMessageRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(workOrderService.addWorkerMessage(workOrderId, request, workOrderService.workerContext(authentication)));
    }

    @GetMapping("/workers/me")
    public ResponseEntity<HumanWorkerResponse> getWorkerMe(Authentication authentication) {
        return ResponseEntity.ok(workOrderService.getWorkerMe(workOrderService.workerContext(authentication)));
    }

    @PostMapping("/workers/me/presence")
    public ResponseEntity<HumanWorkerResponse> updateWorkerPresence(
            @Valid @RequestBody(required = false) UpdateHumanWorkerPresenceRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(workOrderService.updateWorkerPresence(request, workOrderService.workerContext(authentication)));
    }

    @GetMapping("/workers")
    public ResponseEntity<List<HumanWorkerResponse>> workers(Authentication authentication) {
        return ResponseEntity.ok(workOrderService.listWorkers(workOrderService.workerContext(authentication)));
    }
}
