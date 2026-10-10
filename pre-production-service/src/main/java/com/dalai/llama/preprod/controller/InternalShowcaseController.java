package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.service.showcase.ShowcaseSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** For tenant-service's Creator Showcase only (mesh-internal, permitAll like every
 * {@code /api/v1/internal/**} route; never published through the gateway). 404 when the project
 * isn't this tenant's, so a creator can never showcase someone else's film. */
@RestController
public class InternalShowcaseController {

    private final ShowcaseSourceService showcaseSourceService;

    public InternalShowcaseController(ShowcaseSourceService showcaseSourceService) {
        this.showcaseSourceService = showcaseSourceService;
    }

    @GetMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/showcase-source")
    public ShowcaseSourceService.ShowcaseSourceView source(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return showcaseSourceService.source(tenantId, projectId);
    }
}
