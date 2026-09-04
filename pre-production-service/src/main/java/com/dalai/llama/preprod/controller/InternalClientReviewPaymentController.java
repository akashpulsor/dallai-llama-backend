package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.service.ClientReviewSessionService;
import com.dalai.llama.preprod.service.ProjectLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The webhook-driven safety net for the client pay-to-lock and pay-for-extra-review flows: called
 * by billing-service's RazorpayWebhookOrchestrationService once Razorpay's own {@code
 * payment.captured} webhook confirms a client-review payment -- independent of whatever the
 * client's browser managed to do with the synchronous verify call. Without this, a verify call
 * that times out on the client-facing request (see BillingClient's 30s budget) leaves the project
 * permanently unlocked even though the money already moved; funding has the equivalent via a Kafka
 * consumer, this flow doesn't have Kafka wired up at all, so a direct internal call is the simpler
 * fix. Both target methods are already idempotent on their own (ProjectStateMachine treats
 * CLIENT_LOCKED -> CLIENT_LOCKED as a no-op; ClientReviewSessionService#start returns the existing
 * OPEN review instead of minting a second one), so calling either twice -- once from here, once
 * from the client-driven verify path, in either order -- is harmless.
 */
@RestController
public class InternalClientReviewPaymentController {

    private record ClientReviewPaymentCapturedRequest(String kind) {}

    private final ProjectLockService projectLockService;
    private final ClientReviewSessionService clientReviewSessionService;

    public InternalClientReviewPaymentController(
            ProjectLockService projectLockService,
            ClientReviewSessionService clientReviewSessionService
    ) {
        this.projectLockService = projectLockService;
        this.clientReviewSessionService = clientReviewSessionService;
    }

    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/client-review-payment-captured")
    public ResponseEntity<Void> captured(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, @RequestBody ClientReviewPaymentCapturedRequest request) {
        if ("EXTRA_REVIEW".equals(request.kind())) {
            clientReviewSessionService.start(tenantId, projectId, true);
        } else {
            projectLockService.lock(tenantId, projectId);
        }
        return ResponseEntity.noContent().build();
    }
}
