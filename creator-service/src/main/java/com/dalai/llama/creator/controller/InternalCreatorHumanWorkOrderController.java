package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.HumanWorkOrderMessageRequest;
import com.dalai.llama.creator.dto.request.UpdateHumanWorkOrderRequest;
import com.dalai.llama.creator.dto.response.HumanWorkOrderResponse;
import com.dalai.llama.creator.service.CreatorHumanWorkOrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/creator/human-work-orders")
public class InternalCreatorHumanWorkOrderController {

    private final CreatorHumanWorkOrderService workOrderService;

    public InternalCreatorHumanWorkOrderController(CreatorHumanWorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping
    public ResponseEntity<List<HumanWorkOrderResponse>> queue(
            @RequestParam(required = false) String workType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(workOrderService.queue(workType, status, limit));
    }

    @GetMapping("/{workOrderId}")
    public ResponseEntity<HumanWorkOrderResponse> get(@PathVariable UUID workOrderId) {
        return ResponseEntity.ok(workOrderService.getForWorker(workOrderId));
    }

    @PatchMapping("/{workOrderId}")
    public ResponseEntity<HumanWorkOrderResponse> update(
            @PathVariable UUID workOrderId,
            @RequestBody(required = false) UpdateHumanWorkOrderRequest request,
            Authentication authentication
    ) {
        String workerId = authentication == null ? "reviewer" : authentication.getName();
        return ResponseEntity.ok(workOrderService.updateForWorker(workOrderId, request, workerId));
    }

    @PostMapping("/{workOrderId}/messages")
    public ResponseEntity<HumanWorkOrderResponse> addMessage(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody HumanWorkOrderMessageRequest request,
            Authentication authentication
    ) {
        String workerId = authentication == null ? "reviewer" : authentication.getName();
        return ResponseEntity.ok(workOrderService.addWorkerMessage(workOrderId, request, workerId));
    }
}
