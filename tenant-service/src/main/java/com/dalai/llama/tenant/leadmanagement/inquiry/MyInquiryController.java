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
    private final RequestPricing pricing;

    /** What a brand pays upfront on a brief made from a request sent to this creator. */
    public record RequestSettings(@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int prepaymentPercent) {
    }

    @GetMapping("/settings")
    public ResponseEntity<RequestSettings> settings(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> new RequestSettings(pricing.prepaymentPercent(t.getId()))));
    }

    @org.springframework.web.bind.annotation.PutMapping("/settings")
    public ResponseEntity<RequestSettings> saveSettings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RequestSettings request) {
        return ResponseEntity.of(tenant(jwt).map(t -> new RequestSettings(pricing.setPrepaymentPercent(t.getId(), request.prepaymentPercent()))));
    }

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

    /** Closes a request the brand never paid for: its brief link stops working. */
    @PostMapping("/{inquiryId}/expire")
    public ResponseEntity<CreatorInquiryView> expire(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
        return ResponseEntity.of(tenant(jwt).map(t -> inquiryService.expireByCreator(t.getId(), inquiryId)));
    }

    /** An image the brand attached, for the creator who received the request. */
    @GetMapping("/{inquiryId}/images/{imageId}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Jwt jwt, @PathVariable java.util.UUID inquiryId,
                                        @PathVariable java.util.UUID imageId) {
        return tenantService.findByAdminUserId(jwt == null ? "" : jwt.getSubject())
                .flatMap(t -> inquiryService.image(t.getId(), inquiryId, imageId))
                .map(img -> ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(img.contentType()))
                        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                        .body(img.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Takes a request sent to Dalaillama that is offered to this creator; 409 when another was first. */
    @PostMapping("/{inquiryId}/accept")
    public ResponseEntity<CreatorInquiryView> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
        return ResponseEntity.of(tenant(jwt).map(t -> inquiryService.accept(t.getId(), inquiryId)));
    }

    @PostMapping("/{inquiryId}/convert")
    public ResponseEntity<CreatorInquiryView> convert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
        return ResponseEntity.of(tenant(jwt).map(t -> inquiryService.convert(t.getId(), inquiryId)));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
