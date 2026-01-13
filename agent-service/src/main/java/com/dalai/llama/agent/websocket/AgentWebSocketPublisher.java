package com.dalai.llama.agent.websocket;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendTranscript(Long agentId, String callId, String speaker, String text) {
        var payload = new TranscriptPayload(callId, speaker, text);
        String dest = "/topic/agent/" + agentId + "/transcript";
        messagingTemplate.convertAndSend(dest, payload);
        log.debug("WS → {} => {}", dest, payload);
    }

    public record TranscriptPayload(String callId, String speaker, String text) {}
}
