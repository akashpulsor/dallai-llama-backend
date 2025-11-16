package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.IngressCallRequest;
import com.dalai.llama.pbx.core.dto.RouteDecisionResponse;
import com.dalai.llama.pbx.core.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/calls")
@RequiredArgsConstructor
public class CallsController {

    private final RoutingService routingService;

    @PostMapping("/ingress")
    public ResponseEntity<RouteDecisionResponse> ingress(@Valid @RequestBody IngressCallRequest req) {
        return ResponseEntity.ok(routingService.route(req));
    }
}
