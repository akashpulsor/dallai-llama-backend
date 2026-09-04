package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.PublicChatMessageView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView;
import com.dalai.llama.preprod.dto.ReviewCommentView;
import com.dalai.llama.preprod.dto.SendPublicChatMessageRequest;
import com.dalai.llama.preprod.service.PublicProjectService;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Deliberately unauthenticated -- see SecurityConfig's {@code /v1/public/**} carve-out. Possession
 * of the (unguessable) {@code token} is the authorization, same convention as creative-planning-
 * service's PublicProjectRequirementController. This is the client's full review page: the locked
 * (or about-to-be-locked) creative package, plus the chat box once it's locked.
 */
@RestController
public class PublicProjectController {

    private final PublicProjectService publicProjectService;

    public PublicProjectController(PublicProjectService publicProjectService) {
        this.publicProjectService = publicProjectService;
    }

    @GetMapping("/v1/public/projects/{token}")
    public ResponseEntity<PublicProjectPackageView> view(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.view(token));
    }

    /** The client's price to lock this package -- platform base + the creator's margin -- shown
     * before paying. */
    @PostMapping("/v1/public/projects/{token}/lock/quote")
    public ResponseEntity<com.dalai.llama.preprod.service.revenue.BillingClient.Quote> lockQuote(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.quote(token));
    }

    /** Starts the Razorpay order for the lock payment. Nothing locks yet. */
    @PostMapping("/v1/public/projects/{token}/lock/payment")
    public ResponseEntity<com.dalai.llama.preprod.service.revenue.BillingClient.OrderResult> startLockPayment(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.startLockPayment(token));
    }

    /** The pay-gate: verifies the client's payment, credits the creator, then locks. The old bare
     * {@code /lock} route was removed so this is the only way a package can lock. */
    @PostMapping("/v1/public/projects/{token}/lock/verify")
    public ResponseEntity<PublicProjectPackageView> verifyAndLock(
            @PathVariable String token, @RequestBody VerifyLockPaymentRequest request) {
        return ResponseEntity.ok(publicProjectService.verifyPaymentAndLock(
                token, request.gatewayOrderId(), request.gatewayPaymentId(), request.gatewaySignature()));
    }

    public record VerifyLockPaymentRequest(String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {}

    // ---- Transactional client reviews ----

    @GetMapping("/v1/public/projects/{token}/reviews/status")
    public ResponseEntity<com.dalai.llama.preprod.service.ClientReviewSessionService.ReviewStatus> reviewStatus(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.reviewStatus(token));
    }

    @PostMapping("/v1/public/projects/{token}/reviews/start")
    public ResponseEntity<com.dalai.llama.preprod.service.ClientReviewSessionService.ReviewStatus> startReview(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.startReview(token));
    }

    @PostMapping("/v1/public/projects/{token}/reviews/end")
    public ResponseEntity<com.dalai.llama.preprod.service.ClientReviewSessionService.ReviewStatus> endReview(
            @PathVariable String token, @RequestBody(required = false) EndReviewRequest request) {
        boolean satisfied = request != null && Boolean.TRUE.equals(request.satisfied());
        return ResponseEntity.ok(publicProjectService.endReview(token, satisfied));
    }

    @PostMapping("/v1/public/projects/{token}/reviews/payment/quote")
    public ResponseEntity<com.dalai.llama.preprod.service.revenue.BillingClient.Quote> reviewQuote(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.reviewQuote(token));
    }

    @PostMapping("/v1/public/projects/{token}/reviews/payment")
    public ResponseEntity<com.dalai.llama.preprod.service.revenue.BillingClient.OrderResult> startReviewPayment(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.startReviewPayment(token));
    }

    @PostMapping("/v1/public/projects/{token}/reviews/verify")
    public ResponseEntity<com.dalai.llama.preprod.service.ClientReviewSessionService.ReviewStatus> verifyReviewPayment(
            @PathVariable String token, @Valid @RequestBody VerifyLockPaymentRequest request) {
        return ResponseEntity.ok(publicProjectService.verifyReviewPaymentAndStart(
                token, request.gatewayOrderId(), request.gatewayPaymentId(), request.gatewaySignature()));
    }

    public record EndReviewRequest(Boolean satisfied) {}

    @PostMapping("/v1/public/projects/{token}/chat")
    public ResponseEntity<PublicChatMessageView> chat(@PathVariable String token, @Valid @RequestBody SendPublicChatMessageRequest request) {
        return ResponseEntity.ok(toView(publicProjectService.chat(token, request.content())));
    }

    @GetMapping("/v1/public/projects/{token}/chat")
    public ResponseEntity<List<PublicChatMessageView>> chatHistory(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.chatHistory(token).stream().map(this::toView).collect(Collectors.toList()));
    }

    private PublicChatMessageView toView(ChatServiceClient.ChatMessageView message) {
        return new PublicChatMessageView(message.role(), message.content());
    }

    /** The comment feed: a comment plus an optional reference image, left while a review is open.
     * Multipart so the image can ride along with the comment in one request; {@code content} is a
     * plain text part (a JSON DTO would be overkill for one string). */
    @PostMapping(path = "/v1/public/projects/{token}/review-comments", consumes = "multipart/form-data")
    public ResponseEntity<ReviewCommentView> addReviewComment(
            @PathVariable String token,
            @RequestPart("content") String content,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(publicProjectService.addReviewComment(token, content, image));
    }

    @GetMapping("/v1/public/projects/{token}/review-comments")
    public ResponseEntity<List<ReviewCommentView>> reviewComments(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.reviewComments(token));
    }

    /** The client's view of the project's assembled final video. Server enforces the
     * creator's manual download gate ({@link com.dalai.llama.preprod.domain.entity.Project#isFinalVideoDownloadUnlocked})
     * -- {@code videoUrl} is null when locked, so the URL never even reaches the client. */
    @GetMapping("/v1/public/projects/{token}/final-video")
    public ResponseEntity<com.dalai.llama.preprod.service.PublicProjectService.PublicFinalVideoView> finalVideo(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.finalVideo(token));
    }
}
