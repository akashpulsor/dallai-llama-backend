package com.dalai.llama.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO used for assignment requests/responses between pbx-core, agent-service and Kafka.
 * Add/remove fields to match your exact message contract or schema registry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentDto {
    private String tenantId;       // tenant realm / id
    private String callId;         // unique call identifier
    private boolean assigned;      // response: true if an agent was assigned
    private Long agentId;          // assigned agent DB id (nullable if not assigned)
    private String agentExternalId;// Keycloak user id for the agent (nullable)
    private String wssUrl;         // WSS endpoint or tokenized URL for agent notification
    private String preferredMedia; // "webrtc" | "phone" etc.
    private String reason;         // optional reason when not assigned or for audit
    private long ts;               // epoch millis when this DTO was created
}