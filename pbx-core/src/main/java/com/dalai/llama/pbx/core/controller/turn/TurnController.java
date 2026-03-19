package com.dalai.llama.pbx.core.controller.turn;


import com.dalai.llama.pbx.core.service.turn.TurnCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * TURN credentials for Agent UI WebRTC.
 * Agent UI calls this before establishing a WebRTC connection to get fresh
 * ephemeral TURN credentials for media relay through CoTURN.
 */
@RestController
@RequestMapping("/api/v1/turn")
@RequiredArgsConstructor
public class TurnController {

    private final TurnCredentialService turnService;

    @GetMapping("/credentials/{tenantSlug}")
    public ResponseEntity<Map<String, Object>> getCredentials(
            @PathVariable String tenantSlug,
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "false") boolean dedicated) {
        Map<String, Object> creds = turnService.getCredentials(tenantSlug, tenant_id, dedicated);
        return ResponseEntity.ok(creds);
    }
}