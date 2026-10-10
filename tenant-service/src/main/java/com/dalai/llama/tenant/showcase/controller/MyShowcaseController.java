package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.dto.ReorderShowcaseRequest;
import com.dalai.llama.tenant.showcase.dto.UpdateShowcaseItemRequest;
import com.dalai.llama.tenant.showcase.service.ShowcasePickService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The creator's own showcase items. Tenant from the JWT; under the already-routed
 * {@code /api/v1/tenants/me/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/showcase")
public class MyShowcaseController {

    private final TenantService tenantService;
    private final ShowcasePickService pickService;

    @GetMapping
    public ResponseEntity<List<MyShowcaseItemView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> pickService.listMine(t.getId())));
    }

    @PostMapping
    public ResponseEntity<MyShowcaseItemView> pick(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody PickVideoRequest request) {
        return tenant(jwt)
                .map(t -> ResponseEntity.status(HttpStatus.CREATED).body(pickService.pick(t.getId(), request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{itemId}")
    public ResponseEntity<MyShowcaseItemView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId,
                                                     @Valid @RequestBody UpdateShowcaseItemRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> pickService.update(t.getId(), itemId, request)));
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        pickService.remove(tenant.get().getId(), itemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<List<MyShowcaseItemView>> reorder(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody ReorderShowcaseRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> pickService.reorder(t.getId(), request.itemIds())));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
