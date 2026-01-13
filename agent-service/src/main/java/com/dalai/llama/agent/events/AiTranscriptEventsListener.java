package com.dalai.llama.agent.events;

import com.dalai.llama.agent.websocket.AgentWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiTranscriptEventsListener {

    private final AgentWebSocketPublisher wsPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(
            topics = "${app.kafka.topics.ai-transcripts}",
            groupId = "${spring.kafka.consumer.group-id:agent-service-transcripts}"
    )
    public void handleTranscriptEvent(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            String tenantId = json.path("tenantId").asText();
            Long agentId = json.path("agentId").asLong();
            String callId = json.path("callId").asText();
            String text = json.path("text").asText();
            String speaker = json.path("speaker").asText("caller");

            // Send to websocket
            wsPublisher.sendTranscript(agentId, callId, speaker, text);

        } catch (Exception e) {
            log.error("Failed to process transcript event: {}", message, e);
        }
    }
}
