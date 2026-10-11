package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmittedInquiry;
import com.dalai.llama.tenant.showcase.service.FollowService;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Likes (anonymous), follows and video requests (signed-in brands). Under the already-routed
 * {@code /api/v1/public/creators} and {@code /api/v1/public/showcase} prefixes. */
@RestController
@RequiredArgsConstructor
public class PublicEngagementController {

    private final ShowcaseEngagementService engagementService;
    private final FollowService followService;
    private final BrandInquiryService inquiryService;
    private final BrandSessionService sessions;
    private final com.dalai.llama.tenant.leadmanagement.brand.BrandIdentity identity;

    @PutMapping("/api/v1/public/showcase/{publicId}/like")
    public ResponseEntity<ShowcaseEngagementService.LikeState> like(@PathVariable String publicId,
                                                                    @RequestHeader("X-Visitor-Id") UUID visitorId) {
        return ResponseEntity.ok(engagementService.setLiked(publicId, visitorId, true));
    }

    @DeleteMapping("/api/v1/public/showcase/{publicId}/like")
    public ResponseEntity<ShowcaseEngagementService.LikeState> unlike(@PathVariable String publicId,
                                                                      @RequestHeader("X-Visitor-Id") UUID visitorId) {
        return ResponseEntity.ok(engagementService.setLiked(publicId, visitorId, false));
    }

    @PutMapping("/api/v1/public/creators/{handle}/follow")
    public ResponseEntity<FollowService.FollowState> follow(@PathVariable String handle,
                                                            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session) {
        return ResponseEntity.ok(followService.follow(identity.require(authorization, session), handle));
    }

    @DeleteMapping("/api/v1/public/creators/{handle}/follow")
    public ResponseEntity<FollowService.FollowState> unfollow(@PathVariable String handle,
                                                              @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session) {
        return ResponseEntity.ok(followService.unfollow(identity.require(authorization, session), handle));
    }

    @PostMapping("/api/v1/public/creators/{handle}/inquiries")
    public ResponseEntity<SubmittedInquiry> request(@PathVariable String handle,
                                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session,
                                                    @Valid @RequestBody SubmitInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.submit(identity.require(authorization, session), handle, request));
    }

    /** The same request with product and reference images attached (the brief form on a creator's page). */
    @PostMapping(path = "/api/v1/public/creators/{handle}/inquiries", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmittedInquiry> requestWithImages(@PathVariable String handle,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(value = BrandSessionService.COOKIE, required = false) String session,
            @Valid @org.springframework.web.bind.annotation.RequestPart("data") SubmitInquiryRequest request,
            @org.springframework.web.bind.annotation.RequestPart(value = "productImages", required = false) java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
            @org.springframework.web.bind.annotation.RequestPart(value = "referenceImages", required = false) java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.submit(identity.require(authorization, session), handle, request,
                productImages, referenceImages));
    }
}
