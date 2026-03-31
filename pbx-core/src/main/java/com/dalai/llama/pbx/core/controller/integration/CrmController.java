package com.dalai.llama.pbx.core.controller.integration;

import com.dalai.llama.pbx.core.domain.entity.integration.CrmIntegration;
import com.dalai.llama.pbx.core.service.integration.CrmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm")
@RequiredArgsConstructor
public class CrmController {

    private final CrmService crmService;

    // CRUD for CRM integrations
    @PostMapping
    public ResponseEntity<CrmIntegration> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(crmService.create(body));
    }

    @GetMapping
    public ResponseEntity<List<CrmIntegration>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(crmService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CrmIntegration> get(@PathVariable UUID id) {
        return crmService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        crmService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Test connection
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable UUID id) {
        return ResponseEntity.ok(crmService.testConnection(id));
    }

    // Fetch available lists/views from CRM
    @GetMapping("/{id}/lists")
    public ResponseEntity<List<Map<String, Object>>> getLists(@PathVariable UUID id) {
        return ResponseEntity.ok(crmService.fetchLists(id));
    }

    // Import leads from a CRM list into a campaign
    @PostMapping("/{id}/import")
    public ResponseEntity<Map<String, Object>> importLeads(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        UUID campaignId = UUID.fromString(body.get("campaign_id").toString());
        String listId = (String) body.get("list_id");
        int imported = crmService.importLeadsFromCrm(id, campaignId, listId);
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    // Push call result back to CRM
    @PostMapping("/{id}/push-call")
    public ResponseEntity<Map<String, Object>> pushCall(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        String contactId = (String) body.get("contact_id");
        return ResponseEntity.ok(crmService.pushCallResult(id, contactId));
    }
}