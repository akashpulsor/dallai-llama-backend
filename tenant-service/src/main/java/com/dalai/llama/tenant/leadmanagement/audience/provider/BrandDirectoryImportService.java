package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues;
import com.dalai.llama.tenant.leadmanagement.audience.LeadCsvParser;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Bootstrapping (rule 47): ops upload brand contacts into the shared directory -- the same CSV
 * columns creators use (name, designation, company, email, phone, industry, website, country).
 * Every creator's Find brands then finds these brands with their contacts, before (and without)
 * any outside provider. Re-uploading the same people is harmless.
 */
@Service
@RequiredArgsConstructor
public class BrandDirectoryImportService {

    static final String SOURCE = "OPS_IMPORT";
    private static final Set<String> PERSONAL_MAIL = Set.of("gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "icloud.com",
            "rediffmail.com", "live.com", "aol.com", "proton.me", "protonmail.com", "yahoo.co.in");

    private final BrandSearchStore store;
    private final AudienceStore audiences;
    private final RecipientHasher hasher;
    private final Clock clock;

    public record ImportReport(int rows, int companies, int contacts, int rejected) {
    }

    @Transactional
    public ImportReport importCsv(byte[] file) {
        if (file.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Upload a file of at most 2 MB");
        Instant now = clock.instant();
        int companies = 0, contacts = 0, rejected = 0;
        Set<UUID> seen = new java.util.HashSet<>();
        var rows = LeadCsvParser.parse(new String(file, StandardCharsets.UTF_8));
        for (LeadCsvParser.ParsedRow row : rows) {
            String domain = domain(row);
            if (domain == null || !row.hasContact()) {
                rejected++;
                continue;
            }
            UUID company = store.upsertCompany(domain, row.company() != null ? row.company() : domain, row.industry(), row.country(), 0, SOURCE);
            if (seen.add(company)) companies++;
            for (String raw : row.emails()) {
                String email = ContactPointValues.normaliseEmail(raw);
                if (!ContactPointValues.validEmail(email)) continue;
                UUID point = audiences.upsertContactPoint(ContactPointValues.Kind.EMAIL, email, hasher.configured() ? hasher.hash(email) : null,
                        "UNVERIFIED", SOURCE);
                store.linkContact(company, point, row.name(), row.designation(), 100, null);
                contacts++;
            }
            for (String raw : row.phones()) {
                var phone = ContactPointValues.normalisePhone(raw);
                if (phone.isEmpty()) continue;
                UUID point = audiences.upsertContactPoint(ContactPointValues.Kind.PHONE, phone.get(), null, "UNVERIFIED", SOURCE);
                store.linkContact(company, point, row.name(), row.designation(), 100, null);
                contacts++;
            }
            // Known contacts: no outside lookup needed for this company.
            store.markContactsFetched(company, now);
        }
        return new ImportReport(rows.size(), companies, contacts, rejected);
    }

    /** The website's host, else a work email's domain (never a personal mailbox). */
    static String domain(LeadCsvParser.ParsedRow row) {
        if (row.website() != null) {
            try {
                String host = URI.create(row.website()).getHost();
                if (host != null) return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            } catch (IllegalArgumentException ignored) {
                // fall through to the email domain
            }
        }
        for (String raw : row.emails()) {
            String email = ContactPointValues.normaliseEmail(raw);
            if (!ContactPointValues.validEmail(email)) continue;
            String d = ContactPointValues.domainOf(email);
            if (d != null && !PERSONAL_MAIL.contains(d)) return d;
        }
        return null;
    }
}
