package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.PublicProjectRequirementView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately unauthenticated -- see {@code SecurityConfig}'s carve-out for {@code
 * /v1/public/**}. This is the actual "shareable brief page" mechanic: whoever holds the link
 * (the AI_VIDEO_CREATOR path's funder, who has no account here) reads the brief and confirms
 * payment through this path, not the tenant-authenticated one. Possession of the (unguessable,
 * time-boxed) share token is the authorization -- no tenant id, no JWT.
 */
@RestController
public class PublicProjectRequirementController {

    private final ProjectRequirementService projectRequirementService;

    public PublicProjectRequirementController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    @GetMapping("/v1/public/project-requirements/{shareToken}")
    public ResponseEntity<PublicProjectRequirementView> get(@PathVariable String shareToken) {
        return ResponseEntity.ok(projectRequirementService.getByShareToken(shareToken));
    }

    /** Records a payment confirmation -- see {@code ProjectRequirementService}'s class javadoc.
     * The real target for this is a payment provider's webhook once one is integrated, not a
     * button this page's visitor clicks directly. Requires {@code X-Payment-Webhook-Secret} to
     * match the configured secret; with none configured (no payment gateway wired up yet), every
     * call is rejected -- the share token alone is deliberately not enough authorization to mark
     * something funded. This service never processes a payment itself. */
    @PostMapping("/v1/public/project-requirements/{shareToken}/mark-funded")
    public ResponseEntity<PublicProjectRequirementView> markFunded(
            @PathVariable String shareToken,
            @RequestHeader(value = "X-Payment-Webhook-Secret", required = false, defaultValue = "") String webhookSecret) {
        return ResponseEntity.ok(projectRequirementService.markFundedByShareToken(shareToken, webhookSecret, null));
    }
}
