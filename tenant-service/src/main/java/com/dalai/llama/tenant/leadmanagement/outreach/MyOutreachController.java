package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.BuyPackRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.OverviewView;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PackBought;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewView;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendResult;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** The creator's Marketing tab: films they may email, their allowance, preview, send, history and
 * mail packs. Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/outreach")
public class MyOutreachController {

    private final TenantService tenantService;
    private final OutreachService outreachService;

    @GetMapping
    public ResponseEntity<OverviewView> overview(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.overview(t.getId())));
    }

    @PostMapping("/preview")
    public ResponseEntity<PreviewView> preview(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreviewRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.preview(t.getId(), request)));
    }

    @PostMapping("/send")
    public ResponseEntity<SendResult> send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.send(t.getId(), request)));
    }

    @GetMapping("/reach")
    public ResponseEntity<OutreachDtos.ReachView> reach(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.reach(t.getId())));
    }

    @GetMapping("/history")
    public ResponseEntity<List<OutreachStore.IntentRow>> history(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.history(t.getId())));
    }

    /** Buys a pack of mails from the wallet; billing sets the price. Send the same key to retry. */
    @PostMapping("/packs")
    public ResponseEntity<PackBought> buyPack(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BuyPackRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> outreachService.buyPack(t.getId(), request.idempotencyKey())));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
