package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.SignalingConfigResponse;
import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.service.SignalingConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/signaling")
@RequiredArgsConstructor
public class SignalingController {

    private final SignalingConfigService signalingConfigService;

    @GetMapping("/{tenantId}")
    public ResponseEntity<SignalingConfigResponse> getSignalingConfig(
            @PathVariable String tenantId) {

        SignalingConfig cfg = signalingConfigService.getConfigForTenant(tenantId);

        if (cfg == null) {
            return ResponseEntity.notFound().build();
        }

        SignalingConfigResponse response = SignalingConfigResponse.builder()
                .tenantId(cfg.getTenantId())
                .authRealm(cfg.getAuthRealm())
                .wssUrl(cfg.getWssUrl())
                .sipFqdn(cfg.getSipFqdn())
                .stunUrls(cfg.getStunUrls())
                .turnUrls(cfg.getTurnUrls())
                .build();

        return ResponseEntity.ok(response);
    }
}
