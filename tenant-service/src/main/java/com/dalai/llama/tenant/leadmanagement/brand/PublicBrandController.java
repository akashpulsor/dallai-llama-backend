package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.BrandMeView;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.PreferencesRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignedIn;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BrandInquiryView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The brand's side: sign in by email link, then their own details, follows and requests. Public
 * (no Keycloak); the brand is whoever the HttpOnly session cookie says (see BrandSessionService). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/brands")
public class PublicBrandController {

    private final BrandAccountService accounts;
    private final BrandSessionService sessions;
    private final BrandInquiryService inquiries;

    /** Sign up and sign in are one step: we email a one-time link. Always 202. */
    @PostMapping("/sign-in")
    public ResponseEntity<Void> requestSignIn(@Valid @RequestBody SignInRequest request) {
        accounts.requestSignIn(request);
        return ResponseEntity.accepted().build();
    }

    /** The page the emailed link opens posts its token here; the session comes back as a cookie. */
    @PostMapping("/sign-in/{token}")
    public ResponseEntity<SignedIn> completeSignIn(@PathVariable String token) {
        BrandAccountService.SignInResult result = accounts.completeSignIn(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.session()).toString())
                .body(result.view());
    }

    /** Scripts can't touch an HttpOnly cookie, so signing out is the server's job. */
    @PostMapping("/sign-out")
    public ResponseEntity<Void> signOut() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessions.clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<BrandMeView> me(@CookieValue(value = BrandSessionService.COOKIE, required = false) String session) {
        return ResponseEntity.ok(accounts.me(sessions.require(session)));
    }

    @PatchMapping("/me/preferences")
    public ResponseEntity<BrandMeView> preferences(@CookieValue(value = BrandSessionService.COOKIE, required = false) String session,
                                                   @Valid @RequestBody PreferencesRequest request) {
        return ResponseEntity.ok(accounts.updatePreferences(sessions.require(session), request));
    }

    @GetMapping("/me/inquiries")
    public ResponseEntity<List<BrandInquiryView>> myInquiries(
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session) {
        return ResponseEntity.ok(inquiries.forBrand(sessions.require(session)));
    }
}
