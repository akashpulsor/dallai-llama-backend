package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.*;
import com.dalai.llama.pbx.core.service.CallService;
import com.dalai.llama.pbx.core.service.RoutingService;
import com.dalai.llama.pbx.core.util.CallIdGenerator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/calls")
@RequiredArgsConstructor
public class CallsController {

    private final RoutingService routingService;
    private  final CallService callService;

    @PostMapping("/ingress")
    public ResponseEntity<RouteDecisionResponse> ingress(@Valid @RequestBody IngressCallRequest req) {
        return ResponseEntity.ok(routingService.route(req));
    }

    @PostMapping("/outbound")
    public ResponseEntity<?> originateOutbound(
            @RequestHeader("X-Tenant") String tenantId,
            @RequestBody OutboundCallRequest req
    ) {

        // 1. Create Call-ID
        String callId = CallIdGenerator.generate(tenantId);
        req.setCallId(callId);
        callService.originateOutboundCall(req);
        // 2. Prepare Kamailio Originate Request
        return ResponseEntity.ok(callService.originateOutboundCall(req));
    }


    @PostMapping("/{tenantId}/{callId}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable String tenantId,
            @PathVariable String callId,
            @RequestBody RejectCallRequest req) {

        callService.rejectCall(tenantId, callId, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{tenantId}/{callId}/hangup")
    public ResponseEntity<Void> hangup(
            @PathVariable String tenantId,
            @PathVariable String callId,@RequestBody HangupCallRequest req) {

        callService.hangupCall(tenantId, callId,req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{tenantId}/{callId}/transfer")
    public ResponseEntity<Void> transfer(
            @PathVariable String tenantId,
            @PathVariable String callId,
            @RequestBody TransferCallRequest req) {

        callService.transferCall(tenantId, callId, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{tenantId}/{callId}/listen")
    public ResponseEntity<Void> listen(
            @PathVariable String tenantId,
            @PathVariable String callId,
            @RequestBody ListenRequest req) {

        callService.listenCall(tenantId, callId, req);
        return ResponseEntity.ok().build();
    }
}
