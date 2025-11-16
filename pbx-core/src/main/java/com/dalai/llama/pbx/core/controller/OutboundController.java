package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.OutboundCallRequest;
import com.dalai.llama.pbx.core.dto.OutboundRouteResponse;
import com.dalai.llama.pbx.core.service.EgressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/calls")
@RequiredArgsConstructor
public class OutboundController {

    private final EgressService egressService;

    @PostMapping("/egress")
    public ResponseEntity<OutboundRouteResponse> egress(@Valid @RequestBody OutboundCallRequest req) {
        return ResponseEntity.ok(egressService.selectTrunk(req));
    }
}
