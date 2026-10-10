package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.dto.HandleAvailabilityView;
import com.dalai.llama.tenant.showcase.dto.MyPublicProfileView;
import com.dalai.llama.tenant.showcase.dto.UpdatePublicProfileRequest;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** The creator's own public profile. Under {@code /api/v1/tenants/me/**}, so the existing
 * {@code /api/v1/tenants} gateway route already covers it. The tenant always comes from the
 * Keycloak JWT, never from the URL. 404 means the creator has no profile yet, which happens
 * only before they subscribe (or before the one-time backfill has run). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/public-profile")
public class MyPublicProfileController {

    private final TenantService tenantService;
    private final CreatorProfileService profileService;

    @GetMapping
    public ResponseEntity<MyPublicProfileView> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).flatMap(t -> profileService.findMine(t.getId())));
    }

    @PutMapping
    public ResponseEntity<MyPublicProfileView> update(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody UpdatePublicProfileRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> profileService.update(t.getId(), request)));
    }

    @GetMapping("/handle-availability")
    public ResponseEntity<HandleAvailabilityView> handleAvailability(@AuthenticationPrincipal Jwt jwt,
                                                                     @RequestParam String handle) {
        return ResponseEntity.of(tenant(jwt).map(t -> profileService.checkHandle(t.getId(), handle)));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
