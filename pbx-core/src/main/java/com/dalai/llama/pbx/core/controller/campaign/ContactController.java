package com.dalai.llama.pbx.core.controller.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.entity.campaign.DncEntry;
import com.dalai.llama.pbx.core.domain.enums.DncSource;
import com.dalai.llama.pbx.core.service.campaign.ContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping("/campaigns/{campaignId}/contacts/import")
    public ResponseEntity<Map<String, Object>> importContacts(
            @PathVariable UUID campaignId,
            @RequestBody Map<String, Object> body) {
        UUID tenantId = UUID.fromString(body.get("tenant_id").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) body.get("contacts");
        int imported = contactService.importContacts(campaignId, tenantId, contacts);
        return ResponseEntity.ok(Map.of("imported", imported, "total_submitted", contacts.size()));
    }

    @GetMapping("/campaigns/{campaignId}/contacts")
    public ResponseEntity<Page<CampaignContact>> listContacts(
            @PathVariable UUID campaignId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(contactService.getContacts(campaignId, PageRequest.of(page, size)));
    }

    @GetMapping("/campaigns/{campaignId}/contacts/stats")
    public ResponseEntity<Map<String, Long>> contactStats(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(contactService.getContactStats(campaignId));
    }

    // ── DNC ──

    @PostMapping("/dnc")
    public ResponseEntity<DncEntry> addDnc(@RequestBody Map<String, String> body) {
        DncEntry entry = contactService.addToDnc(
                UUID.fromString(body.get("tenant_id")),
                body.get("phone_number"),
                body.get("reason"),
                body.get("source") != null ? DncSource.valueOf(body.get("source")) : DncSource.API,
                body.get("expires_at") != null ? Instant.parse(body.get("expires_at")) : null
        );
        return ResponseEntity.ok(entry);
    }

    @DeleteMapping("/dnc/{tenantId}/{phoneNumber}")
    public ResponseEntity<Void> removeDnc(@PathVariable UUID tenantId, @PathVariable String phoneNumber) {
        contactService.removeFromDnc(tenantId, phoneNumber);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dnc")
    public ResponseEntity<Page<DncEntry>> listDnc(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(contactService.getDncList(tenant_id, PageRequest.of(page, size)));
    }
}