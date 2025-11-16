package com.dalai.llama.agent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RTPVoiceFramesListener {
    
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${app.kafka.topics.rtp-voice-frames}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleRTPFrame(ConsumerRecord<String, String> record) {
        try {
            String payload = record.value();
            Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
            
            String callId = (String) frame.get("callId");
            String tenantId = (String) frame.get("tenantId");
            
            // Forward RTP audio frame to connected agent via WebSocket
            String destination = String.format("/topic/agent/%s/%s/audio", tenantId, callId);
            messagingTemplate.convertAndSend(destination, frame);
            
            log.trace("Forwarded RTP frame for call: {} to WebSocket", callId);
        } catch (Exception e) {
            log.error("Error processing RTP frame: {}", e.getMessage(), e);
        }
    }
}
