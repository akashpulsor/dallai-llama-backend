package com.dalai.llama.creator.domain;

public enum GenerationJobStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
