package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.ranking.VisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The creator's own level, next step and spotlight (the ladder card in creator-ui). */
@RestController
@RequiredArgsConstructor
public class MyVisibilityController {

    private final TenantService tenantService;
    private final VisibilityService visibilityService;

    @GetMapping("/api/v1/tenants/me/visibility")
    public ResponseEntity<VisibilityService.VisibilityView> mine(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(jwt == null ? java.util.Optional.empty()
                : tenantService.findByAdminUserId(jwt.getSubject()).flatMap(t -> visibilityService.mine(t.getId())));
    }
}
