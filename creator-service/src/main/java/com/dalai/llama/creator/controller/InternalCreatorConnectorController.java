package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.domain.entity.CreatorConnectorRunDiff;
import com.dalai.llama.creator.dto.response.ConnectorRunAcceptedResponse;
import com.dalai.llama.creator.dto.response.ConnectorRunDiffResponse;
import com.dalai.llama.creator.service.SourceConnectorOrchestrationService;
import com.dalai.llama.creator.repository.CreatorConnectorRunDiffRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/internal/creator/connectors")
public class InternalCreatorConnectorController {

    private final SourceConnectorOrchestrationService orchestrationService;
    private final CreatorConnectorRunDiffRepository diffRepository;

    public InternalCreatorConnectorController(
            SourceConnectorOrchestrationService orchestrationService,
            CreatorConnectorRunDiffRepository diffRepository
    ) {
        this.orchestrationService = orchestrationService;
        this.diffRepository = diffRepository;
    }

    @PostMapping("/run-once")
    public ResponseEntity<ConnectorRunAcceptedResponse> runOnce() {
        orchestrationService.collectOnce("MANUAL_INTERNAL");
        return ResponseEntity.accepted().body(ConnectorRunAcceptedResponse.builder()
                .status("accepted")
                .triggeredAt(OffsetDateTime.now())
                .build());
    }

    @GetMapping("/diffs/latest")
    public ResponseEntity<List<ConnectorRunDiffResponse>> latestDiffs() {
        return ResponseEntity.ok(diffRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(this::toDiffResponse)
                .toList());
    }

    private ConnectorRunDiffResponse toDiffResponse(CreatorConnectorRunDiff diff) {
        return ConnectorRunDiffResponse.builder()
                .id(diff.getId())
                .connectorRunId(diff.getConnectorRunId())
                .connectorCode(diff.getConnectorCode())
                .targetPlatformCode(diff.getTargetPlatformCode())
                .categoryCode(diff.getCategoryCode())
                .countryCode(diff.getCountryCode())
                .incomingCount(diff.getIncomingCount())
                .newCount(diff.getNewCount())
                .duplicateCount(diff.getDuplicateCount())
                .changedCount(diff.getChangedCount())
                .missingCount(diff.getMissingCount())
                .diffPayload(diff.getDiffPayload())
                .createdAt(diff.getCreatedAt())
                .build();
    }
}
