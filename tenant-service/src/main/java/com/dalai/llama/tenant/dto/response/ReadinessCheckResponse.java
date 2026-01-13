package com.dalai.llama.tenant.dto.response;

public record ReadinessCheckResponse(

        boolean ready,
        String message
) {}
