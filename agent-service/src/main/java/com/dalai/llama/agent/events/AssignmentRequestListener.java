package com.dalai.llama.agent.events;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.service.AssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentRequestListener {
    
    private final AssignmentService assignmentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${app.kafka.topics.assignment-requests}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleAssignmentRequest(ConsumerRecord<String, String> record) {
        try {
            String payload = record.value();
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);
            
            String tenantId = (String) request.get("tenantId");
            String callId = (String) request.get("callId");
            String queueId = (String) request.get("queueId");
            List<String> requiredSkills = (List<String>) request.get("requiredSkills");
            
            log.info("Assignment request received for call: {}, tenant: {}, queue: {}", 
                callId, tenantId, queueId);
            
            Optional<Agent> assignedAgent = assignmentService.assignAgent(tenantId, queueId, requiredSkills);
            
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("callId", callId);
            response.put("tenantId", tenantId);
            
            if (assignedAgent.isPresent()) {
                Agent agent = assignedAgent.get();
                response.put("assigned", true);
                response.put("agentId", agent.getId());
                response.put("agentExternalId", agent.getExternalId());
                response.put("agentUsername", agent.getUsername());
                response.put("wssUrl", "wss://agent-service." + tenantId + "/ws/agent");
                
                // Publish assignment notification
                assignmentService.publishAssignment(tenantId, callId, agent, 
                    response.get("wssUrl").toString());
                    
                log.info("Assigned agent {} to call {}", agent.getId(), callId);
            } else {
                response.put("assigned", false);
                log.warn("No available agent for call {}", callId);
            }
            
            // Send response
            kafkaTemplate.send("agent.assign.responses", tenantId + ":" + callId, response);
            
        } catch (Exception e) {
            log.error("Error processing assignment request: {}", e.getMessage(), e);
        }
    }
}
