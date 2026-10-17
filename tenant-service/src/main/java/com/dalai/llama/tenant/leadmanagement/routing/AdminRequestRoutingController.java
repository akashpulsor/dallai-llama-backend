package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Ops controls for requests sent to Dalaillama: the strategy, which creators take them, and the
 * queue of requests waiting for a creator. Internal only, like the other admin endpoints. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/requests")
public class AdminRequestRoutingController {

    private final RequestRoutingStore store;
    private final RequestRouter router;
    private final CreatorPublicProfileRepository profileRepository;

    public record RoutingView(RoutingStrategy strategy, List<String> acceptingCreators, List<UUID> waiting) {
    }

    public record StrategyRequest(@NotNull RoutingStrategy strategy) {
    }

    public record AcceptingRequest(@NotNull Boolean accepting) {
    }

    public record AssignRequest(@NotBlank String handle) {
    }

    @GetMapping("/routing")
    public RoutingView view() {
        return new RoutingView(store.strategy(), store.acceptingHandles(), store.unassigned());
    }

    @PutMapping("/routing")
    public RoutingView setStrategy(@Valid @RequestBody StrategyRequest request) {
        store.setStrategy(request.strategy());
        return view();
    }

    /** Switches a creator on or off for routed requests. */
    @PutMapping("/creators/{handle}")
    public RoutingView setAccepting(@PathVariable String handle, @Valid @RequestBody AcceptingRequest request) {
        store.setAccepting(creator(handle), request.accepting());
        return view();
    }

    @PostMapping("/{inquiryId}/assign")
    public ResponseEntity<RoutingView> assign(@PathVariable UUID inquiryId, @Valid @RequestBody AssignRequest request) {
        router.assignByOps(inquiryId, creator(request.handle()));
        return ResponseEntity.ok(view());
    }

    private UUID creator(String handle) {
        return profileRepository.findByHandle(handle.trim().toLowerCase(Locale.ROOT)).map(CreatorPublicProfile::getTenantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown creator " + handle));
    }
}
