package com.dalai.llama.tenant.leadmanagement.controller;

import com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Guards the inbound-email webhook boundary. All four failure branches (missing configured
 * secret, bad presented secret, expired/absent timestamp, oversized body) must reject BEFORE
 * the controller does anything with the body. The happy path returns 202 Accepted (drop, don't
 * process -- Phase 1 does not parse the message yet). */
class InternalLeadManagementEmailInboundControllerTest {

    private static final String SECRET = "test-shared-secret-value";
    private static final long MAX_BODY = 100L;
    private static final long TOLERANCE_SECS = 300L;

    private MockMvc mvc;

    private void buildMvcWithConfiguredSecret(String configuredSecret, long maxBody) {
        LeadManagementProperties props = new LeadManagementProperties(
                "partner.dalaillama.in", configuredSecret, maxBody, TOLERANCE_SECS);
        mvc = MockMvcBuilders.standaloneSetup(
                new InternalLeadManagementEmailInboundController(props)).build();
    }

    @BeforeEach
    void setUp() {
        buildMvcWithConfiguredSecret(SECRET, MAX_BODY);
    }

    private String nowTs() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    void returns503WhenSharedSecretNotConfigured() throws Exception {
        buildMvcWithConfiguredSecret("", MAX_BODY);
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable());

        // Also null -- e.g. env var not set.
        buildMvcWithConfiguredSecret(null, MAX_BODY);
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void returns401WhenSecretMissing() throws Exception {
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenSecretWrong() throws Exception {
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", "wrong-secret")
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenTimestampMissing() throws Exception {
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenTimestampMalformed() throws Exception {
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenTimestampOutsideTolerance() throws Exception {
        long tooOld = Instant.now().getEpochSecond() - TOLERANCE_SECS - 60;
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", String.valueOf(tooOld))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns413WhenBodyOverCap() throws Exception {
        // The cap for this test is 100 bytes; send 200.
        String big = "x".repeat(200);
        String json = "{\"raw\":\"" + big + "\"}";
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void returns202OnHappyPath() throws Exception {
        mvc.perform(post("/api/v1/internal/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"a@b\"}"))
                .andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------------
    // Public /webhooks/* path (the one the Cloudflare Worker actually hits;
    // the platform intentionally does not expose /internal/* through the
    // gateway VirtualService). Must run the SAME guards as the internal path.
    // ------------------------------------------------------------------------

    @Test
    void publicPath_returns202OnHappyPath() throws Exception {
        mvc.perform(post("/api/v1/webhooks/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"a@b\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void publicPath_returns401OnMissingSecret() throws Exception {
        mvc.perform(post("/api/v1/webhooks/lead-management/email/inbound")
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPath_returns401OnExpiredTimestamp() throws Exception {
        long tooOld = Instant.now().getEpochSecond() - TOLERANCE_SECS - 60;
        mvc.perform(post("/api/v1/webhooks/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", String.valueOf(tooOld))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPath_returns503WhenSecretNotConfigured() throws Exception {
        // Same fail-safe as the internal path: missing secret => 503, not a silent accept.
        buildMvcWithConfiguredSecret(null, MAX_BODY);
        mvc.perform(post("/api/v1/webhooks/lead-management/email/inbound")
                        .header("X-Webhook-Secret", SECRET)
                        .header("X-Webhook-Timestamp", nowTs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable());
    }
}
