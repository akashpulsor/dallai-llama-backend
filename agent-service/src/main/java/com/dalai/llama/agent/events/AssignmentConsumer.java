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

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AssignmentConsumer {

    private final AssignmentService assignmentService;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    // Consume assignment requests
    @KafkaListener(topics = "${kafka.topic.assignment-requests:agent.assign.requests}", groupId = "agent-service-assign")
    public void onAssignRequest(ConsumerRecord<String, String> record) {
/*        try {
            String msg = record.value();
            Map<String, Object> req = mapper.readValue(msg, Map.class);
            String tenantId = (String) req.get("tenantId");
            String callId = req.get("callId").toString();
            log.debug("Assignment request received for tenant={} callId={}", tenantId, callId);

            Optional<Agent> assigned = assignmentService.assignAgent(tenantId);
            if (assigned.isPresent()) {
                Agent agent = assigned.get();
                assignmentService.publishAssignment(tenantId, callId, agent);

                var resp = Map.of(
                        "callId", callId,
                        "assigned", true,
                        "agentId", agent.getId(),
                        "agentExternalId", agent.getExternalId(),
                        "wssUrl", "wss://agent-service/ws/agent" // replace with tokenized url
                );
                String out = mapper.writeValueAsString(resp);
                kafka.send("${kafka.topic.assignment-responses:agent.assign.responses}", tenantId + ":" + callId, out);
                log.info("Assigned agent {} for call {}", agent.getId(), callId);
            } else {
                var resp = Map.of("callId", callId, "assigned", false);
                String out = mapper.writeValueAsString(resp);
                kafka.send("${kafka.topic.assignment-responses:agent.assign.responses}", tenantId + ":" + callId, out);
                log.info("No agent available for tenant={} call={}", tenantId, callId);
            }
        } catch (Exception e) {
            log.error("Failed to process assignment request: {}", e.getMessage(), e);
        }

 */
    }
}