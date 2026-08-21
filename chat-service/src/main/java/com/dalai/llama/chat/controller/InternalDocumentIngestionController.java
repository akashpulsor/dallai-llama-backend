package com.dalai.llama.chat.controller;

import com.dalai.llama.chat.dto.IngestDocumentRequest;
import com.dalai.llama.chat.service.EmbeddedDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service ingestion into the central embedding index -- same {@code
 * /api/v1/internal/tenants/{tenantId}/...} shape every other service's internal controller
 * already uses (tenantId as an explicit path variable, not from a JWT-derived context, since this
 * path is permitAll for machine callers). Every producing service (creative-planning-service,
 * critic-service, pre-production-service, ...) calls this after it generates/critiques something
 * it wants chattable; only creative-planning-service's marketing-plan generation is wired to call
 * it so far -- see the class javadoc on {@code EmbeddedDocument} for what's still unwired. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalDocumentIngestionController {

    private final EmbeddedDocumentService embeddedDocumentService;

    public InternalDocumentIngestionController(EmbeddedDocumentService embeddedDocumentService) {
        this.embeddedDocumentService = embeddedDocumentService;
    }

    @PostMapping("/embedded-documents")
    public ResponseEntity<Void> ingest(@PathVariable UUID tenantId, @Valid @RequestBody IngestDocumentRequest request) {
        embeddedDocumentService.ingest(tenantId, request);
        return ResponseEntity.noContent().build();
    }
}
