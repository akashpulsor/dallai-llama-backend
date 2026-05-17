package com.dalai.llama.creator.domain;

public enum ConnectorFailurePolicy {
    CONTINUE,
    SKIP_CATEGORY,
    DISABLE_CONNECTOR,
    FAIL_RUN
}
