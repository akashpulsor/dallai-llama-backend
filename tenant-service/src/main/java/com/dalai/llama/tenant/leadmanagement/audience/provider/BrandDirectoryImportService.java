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
import java.util.List;
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

    /** {@code companiesWithoutEmail}: brands kept with no email known (creators see "email not available"). */
    public record ImportReport(int rows, int companies, int contacts, int rejected, int companiesWithoutEmail) {
    }

    /** One brand typed in by ops: only its name and a website (or a work email to take the domain from)
     * are needed; contact details can follow when they're found. */
    public record AddBrandRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160) String company,
            @jakarta.validation.constraints.Size(max = 255) String website,
            @jakarta.validation.constraints.Size(max = 120) String name,
            @jakarta.validation.constraints.Size(max = 120) String designation,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String email,
            @jakarta.validation.constraints.Size(max = 32) String phone,
            com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry,
            @jakarta.validation.constraints.Size(max = 60) String country,
            com.dalai.llama.tenant.leadmanagement.audience.CompanySize size,
            @jakarta.validation.constraints.Size(max = 80) String category) {
    }

    /** {@code contacts}: the email/phone saved with it (0 when none was given: "Email not available"). */
    public record AddBrandResult(UUID companyId, String company, String domain, int contacts) {
    }

    /** What saving one row did. */
    private record Saved(UUID company, int contacts) {
    }

    @Transactional
    public ImportReport importCsv(byte[] file) {
        if (file.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Upload a file of at most 2 MB");
        Instant now = clock.instant();
        int companies = 0, contacts = 0, rejected = 0, withoutEmail = 0;
        Set<UUID> seen = new java.util.HashSet<>();
        var rows = LeadCsvParser.parse(new String(file, StandardCharsets.UTF_8));
        for (LeadCsvParser.ParsedRow row : rows) {
            String domain = domain(row);
            if (domain == null) {
                rejected++;
                continue;
            }
            Saved saved = save(row, domain, now);
            if (seen.add(saved.company())) companies++;
            if (row.emails().isEmpty()) withoutEmail++;
            contacts += saved.contacts();
        }
        return new ImportReport(rows.size(), companies, contacts, rejected, withoutEmail);
    }

    /** One brand from the ops form, saved the way an uploaded row is (an existing brand, by website, gets
     * the new details and contact added). */
    @Transactional
    public AddBrandResult addBrand(AddBrandRequest r) {
        String website = blank(r.website()) ? null : r.website().trim();
        if (website != null && !website.startsWith("http")) website = "https://" + website;
        var row = new LeadCsvParser.ParsedRow(0, null, blank(r.email()) ? List.of() : List.of(r.email().trim()),
                blank(r.phone()) ? List.of() : List.of(r.phone().trim()), blankToNull(r.name()), blankToNull(r.designation()), r.company().trim(),
                r.industry(), website, blank(r.country()) ? null : LeadCsvParser.countryCode(r.country()), r.size(), blankToNull(r.category()));
        String domain = domain(row);
        if (domain == null) throw new IllegalArgumentException("Give the brand's website (or a work email at its domain)");
        if (!blank(r.phone()) && ContactPointValues.normalisePhone(r.phone()).isEmpty()) {
            throw new IllegalArgumentException("That phone number doesn't look right (8 to 15 digits)");
        }
        Saved saved = save(row, domain, clock.instant());
        return new AddBrandResult(saved.company(), row.company(), domain, saved.contacts());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    /** The brand by its domain, and its email/phone as contacts (none given: listed without, for later). */
    private Saved save(LeadCsvParser.ParsedRow row, String domain, Instant now) {
        UUID company = store.upsertCompany(domain, row.company() != null ? row.company() : domain, row.industry(), row.country(), 0, SOURCE,
                row.size(), row.category());
        int contacts = 0;
        // No contact given: the brand is still listed, and left for a provider lookup (or ops) to fill.
        if (!row.hasContact()) return new Saved(company, 0);
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
        return new Saved(company, contacts);
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
