package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmittedInquiry;
import com.dalai.llama.tenant.showcase.service.FollowService;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
                                                            @RequestHeader(value = BrandSessionService.HEADER, required = false) String session) {
        return ResponseEntity.ok(followService.follow(sessions.require(session), handle));
    }

    @DeleteMapping("/api/v1/public/creators/{handle}/follow")
    public ResponseEntity<FollowService.FollowState> unfollow(@PathVariable String handle,
                                                              @RequestHeader(value = BrandSessionService.HEADER, required = false) String session) {
        return ResponseEntity.ok(followService.unfollow(sessions.require(session), handle));
    }

    @PostMapping("/api/v1/public/creators/{handle}/inquiries")
    public ResponseEntity<SubmittedInquiry> request(@PathVariable String handle,
                                                    @RequestHeader(value = BrandSessionService.HEADER, required = false) String session,
                                                    @Valid @RequestBody SubmitInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.submit(sessions.require(session), handle, request));
    }
}
