package com.dalai.llama.creator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorRunDiffResponse {
    private UUID id;
    private UUID connectorRunId;
    private String connectorCode;
    private String targetPlatformCode;
    private String categoryCode;
    private String countryCode;
    private Integer incomingCount;
    private Integer newCount;
    private Integer duplicateCount;
    private Integer changedCount;
    private Integer missingCount;
    private Map<String, Object> diffPayload;
    private OffsetDateTime createdAt;
}
