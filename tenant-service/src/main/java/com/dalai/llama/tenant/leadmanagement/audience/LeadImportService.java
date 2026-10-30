package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.ImportReport;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore.BatchCounts;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues.Kind;
import com.dalai.llama.tenant.leadmanagement.audience.LeadCsvParser.ParsedRow;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Rules 21–23: an uploaded CSV becomes de-duplicated leads in an audience. Rows that share any
 * contact point are one lead (two existing leads touched by one row merge into the older); never
 * merged on name alone. Every row is kept verbatim; malformed emails are kept as INVALID contact
 * points ("discarded"), so the creator sees what was dropped. */
@Service
@RequiredArgsConstructor
public class LeadImportService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private final AudienceStore store;
    private final RecipientHasher hasher;
    private final Clock clock;

    @Transactional
    public ImportReport importCsv(UUID tenantId, UUID audienceId, String fileName, byte[] content) {
        if (!store.owns(tenantId, audienceId)) throw new IllegalArgumentException("Unknown audience");
        if (content.length == 0) throw new IllegalArgumentException("The file is empty");
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("Upload a file of at most 2 MB");
        List<ParsedRow> rows = LeadCsvParser.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8));

        UUID batch = store.insertBatch(tenantId, audienceId, fileName);
        Instant now = clock.instant();
        int imported = 0, rejected = 0, created = 0, merged = 0, added = 0;
        Set<UUID> touched = new LinkedHashSet<>();
        for (ParsedRow row : rows) {
            Set<UUID> points = contactPoints(row);
            if (points.isEmpty() && !row.identifiable()) {
                store.insertSource(batch, tenantId, null, row.rowNumber(), row.raw(), "No email, phone, name or company");
                rejected++;
                continue;
            }
            Placed placed = place(tenantId, audienceId, row, points, now);
            if (placed.created()) created++;
            else merged++;
            added += placed.contactPointsAdded();
            touched.add(placed.leadId());
            store.insertSource(batch, tenantId, placed.leadId(), row.rowNumber(), row.raw(), null);
            imported++;
        }
        BatchCounts counts = new BatchCounts(rows.size(), imported, rejected, created, merged, added);
        store.finishBatch(batch, counts);
        return new ImportReport(batch, counts.total(), counts.imported(), counts.rejected(), counts.leadsCreated(),
                counts.leadsMerged(), counts.contactPointsAdded(), store.memberCount(audienceId), store.countWithoutEmail(touched));
    }

    /** The creator types one lead in (the CSV's columns as fields); it joins the audience exactly as
     * an uploaded row would: an existing lead with the same email or phone, or the same company
     * still waiting for an email, is filled in rather than duplicated. */
    @Transactional
    public AudienceDtos.AddLeadResult addLead(UUID tenantId, UUID audienceId, AudienceDtos.AddLeadRequest r) {
        if (!store.owns(tenantId, audienceId)) throw new IllegalArgumentException("Unknown audience");
        ParsedRow row = new ParsedRow(0, null, blankless(r.email(), "[;,\\s]+"), blankless(r.phone(), "[;,/]+"), blank(r.name()),
                blank(r.designation()), blank(r.company()), r.industry(),
                r.website() == null || r.website().isBlank() ? null : (r.website().trim().startsWith("http") ? r.website().trim() : "https://" + r.website().trim()),
                null, r.size());
        Set<UUID> points = contactPoints(row);
        if (points.isEmpty() && !row.identifiable()) throw new IllegalArgumentException("Give at least a company, a name or an email");
        Placed placed = place(tenantId, audienceId, row, points, clock.instant());
        return new AudienceDtos.AddLeadResult(placed.leadId(), !placed.created(), store.memberCount(audienceId));
    }

    private record Placed(UUID leadId, boolean created, int contactPointsAdded) {
    }

    /** A row joins the lead that already has one of its emails or phones; failing that, the lead for
     * the same company (or person) still waiting for a contact, so an email found later fills it in.
     * With no email or phone the lead is still kept (shown as "email not available"). */
    private Placed place(UUID tenantId, UUID audienceId, ParsedRow row, Set<UUID> points, Instant now) {
        List<UUID> owners = points.isEmpty() ? List.of() : store.leadsOwning(tenantId, points);
        if (owners.isEmpty()) owners = store.leadWithoutContacts(tenantId, row.company(), row.name()).map(List::of).orElse(List.of());
        UUID lead;
        boolean created = owners.isEmpty();
        if (created) {
            lead = store.createLead(tenantId, row.name(), row.designation(), row.company(), row.industry(), row.website(), row.size(), now);
        } else {
            lead = owners.get(0);
            if (owners.size() > 1) store.merge(tenantId, lead, owners.subList(1, owners.size()));
            store.fillBlanks(lead, row.name(), row.designation(), row.company(), row.industry(), row.website(), row.size());
        }
        int added = 0;
        for (UUID point : points) if (store.link(tenantId, lead, point)) added++;
        store.addMember(audienceId, lead);
        return new Placed(lead, created, added);
    }

    private static List<String> blankless(String raw, String separators) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(separators)).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Set<UUID> contactPoints(ParsedRow row) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : row.emails()) {
            String email = ContactPointValues.normaliseEmail(raw);
            boolean valid = ContactPointValues.validEmail(email);
            if (email.length() > 254) continue;
            ids.add(store.upsertContactPoint(Kind.EMAIL, email, hasher.configured() ? hasher.hash(email) : null,
                    valid ? "UNVERIFIED" : "INVALID", valid ? null : "BAD_SYNTAX"));
        }
        for (String raw : row.phones()) {
            Optional<String> phone = ContactPointValues.normalisePhone(raw);
            phone.ifPresent(p -> ids.add(store.upsertContactPoint(Kind.PHONE, p, null, "UNVERIFIED", null)));
        }
        return ids;
    }
}
