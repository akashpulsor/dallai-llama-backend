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
public class TranscriptListener {
    
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${app.kafka.topics.ai-transcripts}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTranscript(ConsumerRecord<String, String> record) {
        try {
            String payload = record.value();
            Map<String, Object> transcript = objectMapper.readValue(payload, Map.class);
            
            String callId = (String) transcript.get("callId");
            String tenantId = (String) transcript.get("tenantId");
            
            // Forward transcript to agent via WebSocket
            String destination = String.format("/topic/agent/%s/%s/transcript", tenantId, callId);
            messagingTemplate.convertAndSend(destination, transcript);
            
            log.debug("Forwarded transcript for call: {} to WebSocket", callId);
        } catch (Exception e) {
            log.error("Error processing transcript: {}", e.getMessage(), e);
        }
    }
}
