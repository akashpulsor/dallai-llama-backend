package com.dalai.llama.tenant.dto.request;

public record TurnCredentials(
        String username, String password,
        String turnUrl, String turnsUrl, String stunUrl,
        int ttl
) {}
