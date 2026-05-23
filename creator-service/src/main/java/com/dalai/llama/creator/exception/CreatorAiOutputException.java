package com.dalai.llama.creator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

public class CreatorAiOutputException extends ResponseStatusException {

    private final Map<String, Object> debugPayload;

    public CreatorAiOutputException(HttpStatus status, String reason, Map<String, Object> debugPayload) {
        super(status, reason);
        this.debugPayload = debugPayload == null ? Map.of() : new LinkedHashMap<>(debugPayload);
    }

    public Map<String, Object> getDebugPayload() {
        return debugPayload;
    }
}
