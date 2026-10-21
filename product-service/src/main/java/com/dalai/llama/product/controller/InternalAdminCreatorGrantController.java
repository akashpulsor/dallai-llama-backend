package com.dalai.llama.product.controller;

import com.dalai.llama.product.service.impl.CreatorVideoGrantService;
import com.dalai.llama.product.service.impl.CreatorVideoGrantService.GrantView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ops (ops.dalaillama.in, behind the dalai_admin gate): give a creator Studio or Pro free for a
 * number of days, see where they stand, end a grant early. */
@RestController
@RequestMapping("/api/v1/internal/admin/subscriptions/creator-video")
@RequiredArgsConstructor
public class InternalAdminCreatorGrantController {

    private final CreatorVideoGrantService grants;

    public record GrantRequest(@NotNull UUID tenantId, @NotBlank String planCode, @Min(1) @Max(3660) int days,
                               @Size(max = 300) String note) {
    }

    public record GrantablePlan(String planCode, String name, String tier) {
    }

    @GetMapping("/plans")
    public List<GrantablePlan> plans() {
        return grants.grantablePlans().stream().map(p -> new GrantablePlan(p.getCode(), p.getName(), p.getTier().name())).toList();
    }

    @GetMapping
    public GrantView current(@RequestParam UUID tenantId) {
        return grants.view(tenantId);
    }

    @PostMapping("/grants")
    public GrantView grant(@Valid @RequestBody GrantRequest request) {
        return grants.grant(request.tenantId(), request.planCode(), request.days(), request.note());
    }

    @PostMapping("/{subscriptionId}/end-grant")
    public GrantView end(@PathVariable UUID subscriptionId) {
        return grants.end(subscriptionId);
    }

    /** A refused grant reads as the reason, for the ops screen to show. */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> refused(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
