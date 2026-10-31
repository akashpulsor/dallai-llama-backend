package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.CreatorView;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendResult;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.TemplateRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Send;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Ops page "Mail creators" tab. Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/creator-mail")
public class AdminCreatorMailController {

    private final CreatorMailService service;

    @GetMapping("/templates")
    public ResponseEntity<List<Template>> templates() {
        return ResponseEntity.ok(service.templates());
    }

    @PostMapping("/templates")
    public ResponseEntity<Template> create(@Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    /** Edit, or switch off with {@code active: false}. */
    @PutMapping("/templates/{templateId}")
    public ResponseEntity<Template> update(@PathVariable UUID templateId, @Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(service.update(templateId, request));
    }

    /** A draft rendered as the recipient will see it (HTML for the ops page to show). */
    @PostMapping("/preview")
    public ResponseEntity<CreatorMailService.Preview> preview(@Valid @RequestBody CreatorMailService.PreviewRequest request) {
        return ResponseEntity.ok(service.preview(request));
    }

    @GetMapping("/creators")
    public ResponseEntity<List<CreatorView>> creators() {
        return ResponseEntity.ok(service.creators());
    }

    /** Creator leads (not on the platform yet) and the queue: how many, sent today, the day's cap. */
    @GetMapping("/overview")
    public ResponseEntity<CreatorMailService.Overview> overview() {
        return ResponseEntity.ok(service.overview());
    }

    /** Creator leads CSV (multipart field {@code file}): email, name, website (their channel or page). */
    @PostMapping(path = "/leads/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreatorMailService.LeadImportResult> importLeads(
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return ResponseEntity.ok(service.importLeads(file.getBytes()));
    }

    @PostMapping("/send")
    public ResponseEntity<SendResult> send(@Valid @RequestBody SendRequest request) {
        return ResponseEntity.ok(service.send(request));
    }

    @GetMapping("/sends")
    public ResponseEntity<List<Send>> sends() {
        return ResponseEntity.ok(service.recentSends());
    }
}
