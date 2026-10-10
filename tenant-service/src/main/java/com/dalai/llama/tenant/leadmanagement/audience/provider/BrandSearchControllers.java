package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/** Brand search for creators and its credit view for ops (rule 42). */
public final class BrandSearchControllers {

    private BrandSearchControllers() {
    }

    /** Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/tenants/me/outreach")
    public static class MyBrandSearchController {

        private final TenantService tenantService;
        private final BrandSearchService brands;

        @GetMapping("/brand-search")
        public ResponseEntity<BrandSearchService.SearchResult> search(@AuthenticationPrincipal Jwt jwt,
                                                                      @RequestParam ShowcaseIndustry industry,
                                                                      @RequestParam(required = false) String country,
                                                                      @RequestParam(required = false) String q) {
            if (country != null && country.length() > 2) throw new IllegalArgumentException("Use a two-letter country code");
            if (q != null && q.length() > 120) throw new IllegalArgumentException("Keep the search under 120 characters");
            return ResponseEntity.of(tenant(jwt).map(t -> brands.search(new BrandSearchService.SearchRequest(industry, country, q))));
        }

        /** Looks up the chosen companies' emails (once, shared) and adds them as leads. */
        @PostMapping("/audiences/{audienceId}/add-companies")
        public ResponseEntity<BrandSearchService.AddResult> add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID audienceId,
                                                                @Valid @RequestBody BrandSearchService.AddRequest request) {
            return ResponseEntity.of(tenant(jwt).map(t -> brands.addToAudience(t.getId(), audienceId, request)));
        }

        private Optional<Tenant> tenant(Jwt jwt) {
            return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
        }
    }

    /** Ops: provider credits this month. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/internal/admin/tenants/outreach/brand-directory")
    public static class AdminBrandSearchController {

        private final BrandSearchService brands;

        @GetMapping("/credits")
        public ResponseEntity<BrandSearchService.Credits> credits() {
            return ResponseEntity.ok(brands.credits());
        }
    }
}
