package com.dalai.llama.billing.domain.entity.enums;

public enum UsageMetric {
    INBOUND_CALL_MINUTES,
    OUTBOUND_CALL_MINUTES,
    AI_STT_SECONDS,
    AI_LLM_TOKENS,
    DID_RENTAL,
    DID_SETUP,
    RECORDING_STORAGE_GB,
    SMS_SENT,
    SMS_RECEIVED
}