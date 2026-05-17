package com.dalai.llama.creator.domain;

public enum ConnectorRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    RATE_LIMITED
}
