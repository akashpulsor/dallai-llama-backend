package com.dalai.llama.agent.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ConferenceRequest {
    private String name;
    private String initialModeratorContact;
    private boolean record;
    private Map<String, Object> metadata;
}
