package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Links inside outreach mail. Public, under its own gateway prefix {@code /api/v1/public/outreach}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/outreach")
public class PublicOutreachController {

    private final OutreachStore store;
    private final OutreachService outreachService;
    private final ShowcaseProperties showcaseProperties;

    /** A film link: counts the click, then on to the creator's page (with ?ref= for attribution). */
    @GetMapping("/r/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token) {
        String target = store.click(token).orElse(showcaseProperties.publicBaseUrl() + "/creators");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /** The "Unsubscribe" link in the footer opens a page with a confirm button (so link scanners
     * that prefetch URLs can't unsubscribe anyone). */
    @GetMapping("/unsubscribe/{token}")
    public ResponseEntity<Void> unsubscribePage(@PathVariable String token) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(showcaseProperties.publicBaseUrl() + "/unsubscribe/" + token)).build();
    }

    /** The confirm button, and RFC 8058 one-click from mail clients. Always 204. */
    @PostMapping("/unsubscribe/{token}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String token) {
        outreachService.unsubscribe(token);
        return ResponseEntity.noContent().build();
    }
}
