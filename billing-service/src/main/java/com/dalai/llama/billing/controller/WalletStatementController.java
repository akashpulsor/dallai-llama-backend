package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.WalletStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The wallet as a statement rather than a scrolling list of identical rows: what went in last,
 * what has been spent since, broken down by the stage of production that spent it, and the
 * individual calls behind it -- downloadable as CSV.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/wallet/statement")
@RequiredArgsConstructor
@Tag(name = "Wallet statement")
public class WalletStatementController {

    private final WalletStatementService walletStatementService;

    @GetMapping
    @Operation(summary = "Last credit, spend since it, and a per-stage breakdown")
    public ResponseEntity<WalletStatementService.StatementView> statement(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(walletStatementService.statement(tenantId));
    }

    @GetMapping("/lines")
    @Operation(summary = "Every billable call in the window, newest first")
    public ResponseEntity<List<WalletStatementService.StatementLine>> lines(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(walletStatementService.lines(tenantId, startOf(from), endOf(to)));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Statement lines as CSV")
    public ResponseEntity<String> export(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        List<WalletStatementService.StatementLine> lines =
                walletStatementService.lines(tenantId, startOf(from), endOf(to));
        String filename = "wallet-statement-%s.csv".formatted(LocalDate.now());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(walletStatementService.csv(lines, "INR"));
    }

    /** Dates in, instants out: a caller asking for "2026-09-14" means the whole of that day, and
     * an exclusive end would silently drop everything spent after midnight on the last day. */
    private Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    private Instant endOf(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusMillis(1);
    }
}
