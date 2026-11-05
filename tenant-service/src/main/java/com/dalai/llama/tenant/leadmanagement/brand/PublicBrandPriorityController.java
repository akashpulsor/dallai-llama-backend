package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.brand.BrandMailPriorityService.MailPriorities;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The brand console's "what should we send you": a signed-in brand's own mail priorities. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/brands/me/mail-priorities")
public class PublicBrandPriorityController {

    private final BrandMailPriorityService priorities;
    private final BrandIdentity identity;

    @GetMapping
    public ResponseEntity<MailPriorities> get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session) {
        return ResponseEntity.ok(priorities.get(identity.require(authorization, session)));
    }

    @PutMapping
    public ResponseEntity<MailPriorities> save(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session, @RequestBody MailPriorities request) {
        return ResponseEntity.ok(priorities.save(identity.require(authorization, session), request));
    }
}
