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
        for (ParsedRow row : rows) {
            Set<UUID> points = contactPoints(row);
            if (points.isEmpty()) {
                store.insertSource(batch, tenantId, null, row.rowNumber(), row.raw(), "No email or phone");
                rejected++;
                continue;
            }
            List<UUID> owners = store.leadsOwning(tenantId, points);
            UUID lead;
            if (owners.isEmpty()) {
                lead = store.createLead(tenantId, row.name(), row.company(), row.industry(), row.website(), now);
                created++;
            } else {
                lead = owners.get(0);
                if (owners.size() > 1) store.merge(tenantId, lead, owners.subList(1, owners.size()));
                store.fillBlanks(lead, row.name(), row.company(), row.industry(), row.website());
                merged++;
            }
            for (UUID point : points) if (store.link(tenantId, lead, point)) added++;
            store.addMember(audienceId, lead);
            store.insertSource(batch, tenantId, lead, row.rowNumber(), row.raw(), null);
            imported++;
        }
        BatchCounts counts = new BatchCounts(rows.size(), imported, rejected, created, merged, added);
        store.finishBatch(batch, counts);
        return new ImportReport(batch, counts.total(), counts.imported(), counts.rejected(), counts.leadsCreated(),
                counts.leadsMerged(), counts.contactPointsAdded(), store.memberCount(audienceId));
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
