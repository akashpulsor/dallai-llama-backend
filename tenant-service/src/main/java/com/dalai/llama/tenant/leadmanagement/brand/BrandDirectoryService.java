package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Ops loads brands for automatic picks from a CSV: {@code email,name,company,industry,country,website}
 * (header row optional; only email and industry are needed). An imported brand gets picks only if
 * {@code autoPicks} is set, and never if the address unsubscribed. Brands that signed up themselves
 * keep their own choices: import only fills blanks. */
@Service
@RequiredArgsConstructor
public class BrandDirectoryService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final BrandContactRepository contacts;
    private final OutreachStore outreachStore;
    private final RecipientHasher hasher;

    public record ImportResult(int added, int updated, int skippedUnsubscribed, List<String> rejectedLines) {
    }

    @Transactional
    public ImportResult importCsv(String csv, boolean autoPicks) {
        int added = 0;
        int updated = 0;
        int skipped = 0;
        List<String> rejected = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].trim();
            if (line.isEmpty() || (n == 0 && line.toLowerCase(Locale.ROOT).startsWith("email"))) continue;
            String[] f = (line + ",,,,,").split(",", -1);
            String email = RecipientHasher.normalise(f[0]);
            ShowcaseIndustry industry = industry(f[3]);
            if (!EMAIL.matcher(email).matches() || industry == null) {
                rejected.add((n + 1) + ": " + line);
                continue;
            }
            if (outreachStore.isSuppressed(hasher.hash(email))) {
                skipped++;
                continue;
            }
            BrandContact existing = contacts.findByEmail(email).orElse(null);
            BrandContact c = existing != null ? existing : BrandContact.builder()
                    .id(UUID.randomUUID()).email(email).source(BrandContact.Source.OPS_IMPORT).build();
            if (c.getContactName() == null && !f[1].isBlank()) c.setContactName(trim(f[1], 80));
            if (c.getCompanyName() == null && !f[2].isBlank()) c.setCompanyName(trim(f[2], 120));
            if (c.getIndustry() == null) c.setIndustry(industry);
            if (c.getCountryCode() == null && f[4].trim().length() == 2) c.setCountryCode(f[4].trim().toUpperCase(Locale.ROOT));
            if (c.getWebsiteUrl() == null && f[5].trim().startsWith("http")) c.setWebsiteUrl(trim(f[5], 255));
            if (existing == null && autoPicks) c.setAutoPicksOptIn(true);
            contacts.save(c);
            if (existing == null) added++;
            else updated++;
        }
        return new ImportResult(added, updated, skipped, rejected);
    }

    private static ShowcaseIndustry industry(String raw) {
        try {
            return ShowcaseIndustry.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('&', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String s, int max) {
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
