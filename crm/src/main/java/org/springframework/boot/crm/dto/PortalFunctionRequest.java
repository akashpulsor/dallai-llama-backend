package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PortalFunctionRequest {
    private String functionName;
    private Map<String, Object> parameters;
}
