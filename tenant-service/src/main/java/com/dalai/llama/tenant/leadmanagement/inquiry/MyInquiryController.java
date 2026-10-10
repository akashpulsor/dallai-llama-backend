package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.CreatorInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.StatusRequest;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The creator's request inbox. Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/inquiries")
public class MyInquiryController {

    private final TenantService tenantService;
    private final BrandInquiryService inquiryService;

    @GetMapping
    public ResponseEntity<List<CreatorInquiryView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> inquiryService.forCreator(t.getId())));
    }

    @PatchMapping("/{inquiryId}")
    public ResponseEntity<Void> setStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId,
                                          @Valid @RequestBody StatusRequest request) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        inquiryService.setStatus(tenant.get().getId(), inquiryId, request.status());
        return ResponseEntity.noContent().build();
    }

    /** Turns the request into a brief and emails the brand its link. */
    @PostMapping("/{inquiryId}/convert")
    public ResponseEntity<CreatorInquiryView> convert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
        return ResponseEntity.of(tenant(jwt).map(t -> inquiryService.convert(t.getId(), inquiryId)));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
