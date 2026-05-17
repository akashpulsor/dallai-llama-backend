package com.dalai.llama.creator.connector;

import java.util.List;
import java.util.Map;

public record ConnectorFetchResult(
        int requestCount,
        Integer httpStatus,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        Map<String, Object> structuredPayload,
        List<StructuredTrendSignal> signals
) {
}
