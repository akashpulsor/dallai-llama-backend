package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.payment.RazorpayService;
import com.dalai.llama.billing.service.payment.RazorpayWebhookOrchestrationService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Payment gateway webhook handlers")
@Hidden
public class RazorpayWebhookController {

    private final RazorpayService razorpayService;
    private final RazorpayWebhookOrchestrationService webhookService;

    @PostMapping
    @Operation(summary = "Razorpay webhook", description = "Handle Razorpay payment events")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestBody String payload
    ) {
        log.info("Received Razorpay webhook");

        try {
            razorpayService.verifyWebhookSignature(payload, signature);
        } catch (Exception e) {
            log.warn("Rejected Razorpay webhook: invalid signature", e);
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        try {
            String eventType = razorpayService.extractEventType(payload);

            log.info("Processing Razorpay event: {}", eventType);

            webhookService.processWebhook(eventType, payload);

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.internalServerError()
                    .body("Error processing webhook");
        }
    }
}