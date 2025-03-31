package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class IntentStateTransitionRequest {
    private Long currentIntentId;
    private Long nextIntentId;
    private Map<String, Object> transitionData;
}
