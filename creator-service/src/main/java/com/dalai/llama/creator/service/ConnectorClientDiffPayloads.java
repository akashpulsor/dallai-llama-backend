package com.dalai.llama.creator.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConnectorClientDiffPayloads {

    private ConnectorClientDiffPayloads() {
    }

    static Map<String, Object> payload(List<String> newKeys, List<String> duplicateKeys) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("newDedupeKeys", List.copyOf(newKeys));
        payload.put("duplicateDedupeKeys", List.copyOf(duplicateKeys));
        payload.put("changedDedupeKeys", List.of());
        payload.put("missingDedupeKeys", List.of());
        return payload;
    }
}
