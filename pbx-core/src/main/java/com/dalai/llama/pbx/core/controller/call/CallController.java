package com.dalai.llama.pbx.core.controller.call;


import com.dalai.llama.pbx.core.service.call.CallControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallController {

    private final CallControlService callControl;

    @PostMapping("/originate")
    public ResponseEntity<Map<String, String>> originate(@RequestBody Map<String, String> body) {
        String jobUuid = callControl.originate(
                body.get("from"),
                body.get("to"),
                UUID.fromString(body.get("tenant_id")),
                body.get("context"),
                null
        );
        return ResponseEntity.ok(Map.of("job_uuid", jobUuid));
    }

    @PostMapping("/{callId}/answer")
    public ResponseEntity<Map<String, String>> answer(@PathVariable String callId) {
        String result = callControl.answer(callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/{callId}/hold")
    public ResponseEntity<Map<String, String>> hold(@PathVariable String callId) {
        String result = callControl.hold(callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/{callId}/transfer")
    public ResponseEntity<Map<String, String>> transfer(@PathVariable String callId,
                                                        @RequestBody Map<String, String> body) {
        String result = callControl.transfer(callId, body.get("destination"), body.get("context"));
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/{callId}/hangup")
    public ResponseEntity<Map<String, String>> hangup(@PathVariable String callId) {
        String result = callControl.hangup(callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/{callId}/dtmf")
    public ResponseEntity<Map<String, String>> dtmf(@PathVariable String callId,
                                                    @RequestBody Map<String, String> body) {
        String result = callControl.sendDtmf(callId, body.get("digits"));
        return ResponseEntity.ok(Map.of("result", result));
    }

    /**
     * voice-brain calls this for barge-in — stops current TTS playback.
     */
    @PostMapping("/{callId}/interrupt")
    public ResponseEntity<Map<String, String>> interrupt(@PathVariable String callId) {
        String result = callControl.interrupt(callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @GetMapping("/active")
    public ResponseEntity<List<Map<Object, Object>>> activeCalls(
            @RequestParam UUID tenant_id) {
        return ResponseEntity.ok(callControl.getActiveCalls(tenant_id));
    }
}