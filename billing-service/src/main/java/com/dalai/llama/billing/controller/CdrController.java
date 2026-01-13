package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.dto.response.CdrResponse;
import com.dalai.llama.billing.repository.CdrRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/cdrs")
@RequiredArgsConstructor
@Tag(name = "CDRs", description = "Call Detail Record APIs")
public class CdrController {

    private final CdrRepository cdrRepository;

    @GetMapping
    @Operation(summary = "List CDRs", description = "Get paginated list of CDRs with optional filters")
    public ResponseEntity<Page<CdrResponse>> listCdrs(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pageable
    ) {
        // For now, fetch all and filter in memory (production would use JPA Specifications)
        List<Cdr> allCdrs = cdrRepository.findAll().stream()
                .filter(c -> c.getTenantId().equals(tenantId))
                .filter(c -> direction == null || direction.equalsIgnoreCase(c.getDirection()))
                .filter(c -> status == null || (c.getStatus() != null && status.equalsIgnoreCase(c.getStatus().name())))
                .filter(c -> from == null || !c.getInitiatedAt().isBefore(from))
                .filter(c -> to == null || !c.getInitiatedAt().isAfter(to))
                .sorted((a, b) -> b.getInitiatedAt().compareTo(a.getInitiatedAt()))
                .toList();

        List<CdrResponse> responses = allCdrs.stream()
                .map(this::toCdrResponse)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), responses.size());

        return ResponseEntity.ok(new PageImpl<>(
                responses.subList(start, end),
                pageable,
                responses.size()
        ));
    }

    @GetMapping("/{cdrId}")
    @Operation(summary = "Get CDR details", description = "Retrieve specific CDR details")
    public ResponseEntity<CdrResponse> getCdr(
            @PathVariable UUID tenantId,
            @PathVariable UUID cdrId
    ) {
        Cdr cdr = cdrRepository.findById(cdrId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("CDR not found"));

        return ResponseEntity.ok(toCdrResponse(cdr));
    }

    @GetMapping("/export")
    @Operation(summary = "Export CDRs as CSV", description = "Export CDRs for a date range as CSV")
    public ResponseEntity<String> exportCdrs(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        List<Cdr> cdrs = cdrRepository.findAll().stream()
                .filter(c -> c.getTenantId().equals(tenantId))
                .filter(c -> from == null || !c.getInitiatedAt().isBefore(from))
                .filter(c -> to == null || !c.getInitiatedAt().isAfter(to))
                .sorted((a, b) -> b.getInitiatedAt().compareTo(a.getInitiatedAt()))
                .toList();

        StringWriter writer = new StringWriter();
        writer.append("id,call_id,direction,from_number,to_number,initiated_at,duration_seconds,billable_seconds,call_cost,ai_cost,total_cost,status\n");

        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        for (Cdr cdr : cdrs) {
            writer.append(String.format("%s,%s,%s,%s,%s,%s,%d,%d,%s,%s,%s,%s\n",
                    cdr.getId(),
                    escapeCsv(cdr.getCallId()),
                    escapeCsv(cdr.getDirection()),
                    escapeCsv(cdr.getFromNumber()),
                    escapeCsv(cdr.getToNumber()),
                    cdr.getInitiatedAt() != null ? formatter.format(cdr.getInitiatedAt()) : "",
                    cdr.getDurationSeconds(),
                    cdr.getBillableSeconds(),
                    cdr.getCallCost(),
                    cdr.getAiCost(),
                    cdr.getTotalCost(),
                    cdr.getStatus() != null ? cdr.getStatus().name() : ""
            ));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cdrs.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(writer.toString());
    }

    private CdrResponse toCdrResponse(Cdr cdr) {
        return CdrResponse.builder()
                .id(cdr.getId())
                .callId(cdr.getCallId())
                .direction(cdr.getDirection())
                .fromNumber(cdr.getFromNumber())
                .toNumber(cdr.getToNumber())
                .didNumber(cdr.getDidNumber())
                .initiatedAt(cdr.getInitiatedAt())
                .answeredAt(cdr.getAnsweredAt())
                .endedAt(cdr.getEndedAt())
                .durationSeconds(cdr.getDurationSeconds())
                .billableSeconds(cdr.getBillableSeconds())
                .status(cdr.getStatus() != null ? cdr.getStatus().name() : null)
                .destinationType(cdr.getDestinationType() != null ? cdr.getDestinationType().name() : null)
                .appliedRate(cdr.getAppliedRate())
                .callCost(cdr.getCallCost())
                .aiSttSeconds(cdr.getAiSttSeconds())
                .aiLlmTokens(cdr.getAiLlmTokens())
                .aiCost(cdr.getAiCost())
                .totalCost(cdr.getTotalCost())
                .build();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
