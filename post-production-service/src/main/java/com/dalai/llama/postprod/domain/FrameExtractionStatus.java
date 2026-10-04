package com.dalai.llama.postprod.domain;

public enum FrameExtractionStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }
}
