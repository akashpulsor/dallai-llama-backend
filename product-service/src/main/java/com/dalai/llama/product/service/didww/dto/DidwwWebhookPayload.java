package com.dalai.llama.product.service.didww.dto;


import lombok.Data;

@Data
public class DidwwWebhookPayload {

    private String event;
    private String resourceType;
    private String resourceId;
    private String status;
    private String occurredAt;
}
