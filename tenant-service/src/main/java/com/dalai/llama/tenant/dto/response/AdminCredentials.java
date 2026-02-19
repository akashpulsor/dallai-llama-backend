package com.dalai.llama.tenant.dto.response;

public record AdminCredentials(
        String email,
        String temporaryPassword,
        String loginUrl
) {}
