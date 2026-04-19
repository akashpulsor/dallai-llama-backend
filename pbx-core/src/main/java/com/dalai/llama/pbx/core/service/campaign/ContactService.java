package com.dalai.llama.pbx.core.service.campaign;

import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.entity.campaign.DncEntry;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.domain.enums.DncSource;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.campaign.DncEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Contact and DNC list management.
 *
 * Contacts are the outbound dial list for campaigns.
 * DNC (Do-Not-Call) entries are tenant-scoped and checked on every outbound call.
 *
 * When a number is added to DNC, all PENDING contacts with that number
 * across all campaigns for the tenant are automatically marked as DNC.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final CampaignContactRepository contactRepository;
    private final CampaignRepository campaignRepository;
    private final DncEntryRepository dncRepository;

    // ═══════════════════════════════════════════════════════════
    // CONTACT IMPORT
    // ═══════════════════════════════════════════════════════════

    /**
     * Bulk import contacts for a campaign.
     * Skips duplicates (same phone number in same campaign) and DNC numbers.
     * Returns count of successfully imported contacts.
     */
    @Transactional
    public int importContacts(UUID campaignId, UUID tenantId, List<Map<String, Object>> contactRows) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

        int imported = 0;
        int skippedDuplicate = 0;
        int skippedDnc = 0;

        for (Map<String, Object> row : contactRows) {
            String phoneNumber = String.valueOf(row.get("phone_number"));
            if (phoneNumber == null || phoneNumber.isBlank()) continue;

            // Skip duplicate
            if (contactRepository.existsByCampaignIdAndPhoneNumber(campaignId, phoneNumber)) {
                skippedDuplicate++;
                continue;
            }

            // Skip DNC
            if (dncRepository.isOnDncList(tenantId, phoneNumber, Instant.now())) {
                skippedDnc++;
                continue;
            }

            CampaignContact contact = CampaignContact.builder()
                    .campaign(campaign)
                    .tenantId(tenantId)
                    .phoneNumber(phoneNumber)
                    .name(safeString(row, "name"))
                    .email(safeString(row, "email"))
                    .company(safeString(row, "company"))
                    .priority(safeInt(row, "priority", 0))
                    .build();

            // Custom fields go into custom_data JSONB
            Map<String, Object> customData = new HashMap<>(row);
            customData.remove("phone_number");
            customData.remove("name");
            customData.remove("email");
            customData.remove("company");
            customData.remove("priority");
            if (!customData.isEmpty()) {
                contact.setCustomData(customData);
            }

            contactRepository.save(contact);
            imported++;
        }

        // Update campaign total_contacts
        long total = contactRepository.countByCampaignId(campaignId);
        campaign.setTotalContacts((int) total);
        campaignRepository.save(campaign);

        log.info("Imported {} contacts for campaign {} (skipped: {} duplicate, {} DNC)",
                imported, campaignId, skippedDuplicate, skippedDnc);
        return imported;
    }

    // ═══════════════════════════════════════════════════════════
    // CONTACT QUERIES
    // ═══════════════════════════════════════════════════════════

    public Page<CampaignContact> getContacts(UUID campaignId, Pageable pageable) {
        return contactRepository.findByCampaignId(campaignId, pageable);
    }

    public Page<CampaignContact> getContactsByStatus(UUID campaignId, ContactStatus status, Pageable pageable) {
        return contactRepository.findByCampaignIdAndStatus(campaignId, status, pageable);
    }

    public Map<String, Long> getContactStats(UUID campaignId) {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (ContactStatus status : ContactStatus.values()) {
            stats.put(status.name(), contactRepository.countByCampaignIdAndStatus(campaignId, status));
        }
        stats.put("TOTAL", contactRepository.countByCampaignId(campaignId));
        stats.put("DIALABLE", contactRepository.countDialableContacts(campaignId));
        return stats;
    }

    // ═══════════════════════════════════════════════════════════
    // DNC MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    /**
     * Add a number to DNC list.
     * Also marks all PENDING contacts with this number as DNC across all campaigns.
     */
    @Transactional
    public DncEntry addToDnc(UUID tenantId, String phoneNumber, String reason, DncSource source, Instant expiresAt) {
        // Check if already on DNC
        if (dncRepository.existsByTenantIdAndPhoneNumber(tenantId, phoneNumber)) {
            log.debug("Phone {} already on DNC for tenant {}", phoneNumber, tenantId);
            return dncRepository.findByTenantIdAndPhoneNumber(tenantId, phoneNumber).orElseThrow();
        }

        DncEntry entry = DncEntry.builder()
                .tenantId(tenantId)
                .phoneNumber(phoneNumber)
                .reason(reason)
                .source(source)
                .expiresAt(expiresAt)
                .build();
        entry = dncRepository.save(entry);

        // Mark all pending contacts with this number as DNC
        int marked = contactRepository.markAsDnc(tenantId, phoneNumber);
        if (marked > 0) {
            log.info("DNC added for {} — marked {} pending contacts", phoneNumber, marked);
        }

        return entry;
    }

    @Transactional
    public void removeFromDnc(UUID tenantId, String phoneNumber) {
        dncRepository.deleteByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        log.info("Removed {} from DNC for tenant {}", phoneNumber, tenantId);
    }

    public boolean isOnDnc(UUID tenantId, String phoneNumber) {
        return dncRepository.isOnDncList(tenantId, phoneNumber, Instant.now());
    }

    public Page<DncEntry> getDncList(UUID tenantId, Pageable pageable) {
        return dncRepository.findByTenantId(tenantId, pageable);
    }

    // ═══════════════════════════════════════════════════════════
    // CSV / EXCEL IMPORT
    // ═══════════════════════════════════════════════════════════

    /**
     * Parse CSV file and import contacts.
     * Expected header row: phone_number, name, email, company, priority, ... (custom fields)
     */
    @Transactional
    public Map<String, Object> importFromCsv(UUID campaignId, UUID tenantId, InputStream inputStream) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true)
                .build().parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                Map<String, Object> row = new LinkedHashMap<>();
                record.toMap().forEach(row::put);
                rows.add(row);
            }
        }
        int imported = importContacts(campaignId, tenantId, rows);
        return Map.of("imported", imported, "total_submitted", rows.size(), "source", "CSV");
    }

    /**
     * Parse Excel (.xlsx / .xls) file and import contacts.
     * First row is header: phone_number, name, email, company, priority, ... (custom fields)
     */
    @Transactional
    public Map<String, Object> importFromExcel(UUID campaignId, UUID tenantId, InputStream inputStream) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return Map.of("imported", 0, "error", "No header row");

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cell.getStringCellValue().trim().toLowerCase());
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j);
                    rowMap.put(headers.get(j), cellToString(cell));
                }
                if (rowMap.get("phone_number") != null) {
                    rows.add(rowMap);
                }
            }
        }
        int imported = importContacts(campaignId, tenantId, rows);
        return Map.of("imported", imported, "total_submitted", rows.size(), "source", "EXCEL");
    }

    // ═══════════════════════════════════════════════════════════
    // CSV EXPORT
    // ═══════════════════════════════════════════════════════════

    /**
     * Export contacts as CSV bytes.
     * If status is provided, exports only contacts with that status.
     */
    public byte[] exportToCsv(UUID campaignId, ContactStatus status) {
        List<CampaignContact> contacts;
        if (status != null) {
            contacts = contactRepository.findByCampaignIdAndStatus(campaignId, status, Pageable.unpaged()).getContent();
        } else {
            contacts = contactRepository.findByCampaignId(campaignId, Pageable.unpaged()).getContent();
        }

        try (StringWriter sw = new StringWriter();
             CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
                     .setHeader("phone_number", "name", "email", "company", "status", "disposition",
                             "attempt_count", "duration_seconds", "call_id", "notes", "priority",
                             "assigned_agent_id", "source", "crm_id", "crm_provider", "created_at")
                     .build())) {
            for (CampaignContact c : contacts) {
                printer.printRecord(
                        c.getPhoneNumber(), c.getName(), c.getEmail(), c.getCompany(),
                        c.getStatus(), c.getDisposition(), c.getAttemptCount(),
                        c.getDurationSeconds(), c.getCallId(), c.getNotes(),
                        c.getPriority(), c.getAssignedAgentId(),
                        c.getSource(), c.getCrmId(), c.getCrmProvider(), c.getCreatedAt()
                );
            }
            printer.flush();
            return sw.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("CSV export failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEAD QUALIFICATION
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void qualifyContact(UUID contactId, boolean qualified, String notes) {
        contactRepository.findById(contactId).ifPresent(c -> {
            c.setStatus(qualified ? ContactStatus.QUALIFIED : ContactStatus.NOT_QUALIFIED);
            if (notes != null) c.setNotes(notes);
            contactRepository.save(c);
            log.info("Contact {} marked as {}", contactId, c.getStatus());
        });
    }

    @Transactional
    public void assignToAgent(UUID contactId, UUID agentId) {
        contactRepository.findById(contactId).ifPresent(c -> {
            c.setAssignedAgentId(agentId);
            contactRepository.save(c);
            log.info("Contact {} assigned to agent {}", contactId, agentId);
        });
    }

    /**
     * Cross-campaign qualified leads for a tenant.
     */
    public Page<CampaignContact> getQualifiedLeads(UUID tenantId, Pageable pageable) {
        return contactRepository.findByTenantIdAndStatus(tenantId, ContactStatus.QUALIFIED, pageable);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════

    private String safeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private int safeInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private String cellToString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }
}