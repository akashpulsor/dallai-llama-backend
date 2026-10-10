package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.dto.PublicCreatorProfileView;
import com.dalai.llama.tenant.showcase.dto.RecordPlayRequest;
import com.dalai.llama.tenant.showcase.dto.ShowcaseFeedPage;
import com.dalai.llama.tenant.showcase.service.PublicShowcaseService;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
import com.dalai.llama.tenant.showcase.service.ShowcaseModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** No-login endpoints behind the public site. {@code /api/v1/public/**} is already permitAll in
 * SecurityConfig; the gateway routes each prefix here separately (never the bare
 * {@code /api/v1/public}, see the prefix-collision note in the chart values). */
@RestController
@RequiredArgsConstructor
public class PublicShowcaseController {

    private final PublicShowcaseService showcaseService;
    private final ShowcaseEngagementService engagementService;
    private final ShowcaseModerationService moderation;
    private final com.dalai.llama.tenant.showcase.service.LandingAssembler landingAssembler;

    /** A handle the creator gave up within the redirect window answers 301 to the new one, so old
     * links in mails keep working; the body's {@code handle} is the current one. */
    @GetMapping("/api/v1/public/creators/{handle}")
    public ResponseEntity<PublicCreatorProfileView> creator(@PathVariable String handle) {
        java.util.Optional<PublicCreatorProfileView> profile = showcaseService.profile(handle);
        if (profile.isPresent()) return ResponseEntity.ok(profile.get());
        return showcaseService.movedHandle(handle)
                .map(current -> ResponseEntity.status(org.springframework.http.HttpStatus.MOVED_PERMANENTLY)
                        .header(org.springframework.http.HttpHeaders.LOCATION, "/api/v1/public/creators/" + current)
                        .<PublicCreatorProfileView>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record ReportRequest(@jakarta.validation.constraints.NotNull ShowcaseModerationService.ReportReason reason,
                                @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    /** "Report this video" (rule 18): one per visitor per item; enough distinct reports hide it until
     * ops reviews it. */
    @PostMapping("/api/v1/public/showcase/{publicId}/reports")
    public ResponseEntity<Void> report(@PathVariable String publicId,
                                       @RequestHeader("X-Visitor-Id") UUID visitorId,
                                       @jakarta.validation.Valid @RequestBody ReportRequest request) {
        moderation.report(publicId, visitorId, request.reason(), request.note());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/public/showcase")
    public ShowcaseFeedPage feed(@RequestParam(defaultValue = "NEW") PublicShowcaseService.FeedTab tab,
                                 @RequestParam(required = false) ShowcaseIndustry industry,
                                 @RequestParam(required = false) ShowcaseFormat format,
                                 @RequestParam(defaultValue = "0") int page) {
        return showcaseService.feed(tab, industry, format, page);
    }

    /** The landing page's grid (rules 12-14): pinned, spotlights, exploration, then by score from
     * creators at or above the landing floor. Empty while too few films qualify: hide the section. */
    @GetMapping("/api/v1/public/showcase/landing")
    public java.util.List<com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard> landing() {
        return landingAssembler.assemble();
    }

    /** Only when the SELF host fallback is on (rule 5): a short-lived link to our own copy of a
     * platform film. 404 otherwise; the card's {@code host} says which to use. */
    @GetMapping("/api/v1/public/showcase/{publicId}/source")
    public ResponseEntity<PublicShowcaseService.SelfSourceView> source(@PathVariable String publicId) {
        return ResponseEntity.of(showcaseService.selfSource(publicId));
    }

    /** {@code X-Visitor-Id} is a random UUID the browser keeps in local storage; it identifies
     * nobody and only stops one visitor's reloads from counting twice. */
    @PostMapping("/api/v1/public/showcase/{publicId}/plays")
    public ResponseEntity<Void> play(@PathVariable String publicId,
                                     @RequestHeader("X-Visitor-Id") UUID visitorId,
                                     @RequestBody(required = false) RecordPlayRequest request) {
        engagementService.recordPlay(publicId, visitorId, request != null && request.completed());
        return ResponseEntity.noContent().build();
    }
}
