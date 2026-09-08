package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.service.TestVoiceService;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Video-page "Test voice" preview -- given a shot, returns a short audio sample rendered with the
 * character's real voice identity (cloned from the uploaded actor sample, or direct TTS with the
 * built-in voice pick, exactly matching what {@link com.dalai.llama.videogen.service.BeatDubbingService}
 * would do at approve() time). Lives under a fresh {@code /v1/voice-tests} prefix so it doesn't
 * collide with pre-production-service's {@code /v1/shots} or post-production-service's
 * {@code /v1/dubbing} route ownership at the gateway.
 */
@RestController
public class TestVoiceController {

    private final TestVoiceService testVoiceService;

    public TestVoiceController(TestVoiceService testVoiceService) {
        this.testVoiceService = testVoiceService;
    }

    @PostMapping("/v1/voice-tests")
    public ResponseEntity<TestVoiceService.TestVoiceResult> testVoice(@RequestBody @NotNull TestVoiceRequest request) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return ResponseEntity.ok(testVoiceService.testVoice(tenantId, request.projectId(), request.shotId(), request.text()));
    }

    public record TestVoiceRequest(UUID projectId, UUID shotId, String text) {
    }
}
