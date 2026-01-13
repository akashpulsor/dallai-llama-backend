package com.dalai.llama.billing.domain.exception;

public class DuplicateCdrException extends RuntimeException {

    public DuplicateCdrException(String callId) {
        super("CDR already processed for call: " + callId);
    }
}
