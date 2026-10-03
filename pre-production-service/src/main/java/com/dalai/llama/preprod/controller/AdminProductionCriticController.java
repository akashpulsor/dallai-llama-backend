package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.service.critic.ProductionCriticSwitch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The ops page's switch for production's critics (see {@link ProductionCriticSwitch}). Ops
 * perimeter: /api/v1/internal/admin/** is reached only through ops.dalaillama.in's
 * oauth2-proxy-gated VirtualService. */
@RestController
@RequestMapping("/api/v1/internal/admin/production/critics")
public class AdminProductionCriticController {

    private final ProductionCriticSwitch productionCriticSwitch;

    public AdminProductionCriticController(ProductionCriticSwitch productionCriticSwitch) {
        this.productionCriticSwitch = productionCriticSwitch;
    }

    @GetMapping
    public ResponseEntity<CriticsView> get() {
        return ResponseEntity.ok(new CriticsView(productionCriticSwitch.enabled()));
    }

    @PutMapping
    public ResponseEntity<CriticsView> set(@Valid @RequestBody UpdateCriticsRequest request) {
        return ResponseEntity.ok(new CriticsView(productionCriticSwitch.set(request.enabled())));
    }

    public record CriticsView(boolean enabled) {}

    public record UpdateCriticsRequest(@NotNull Boolean enabled) {}
}
