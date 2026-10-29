package com.dalai.llama.tenant.leadmanagement.audience;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How big a brand is, for directory companies and creators' leads. Read from a CSV "size" column:
 * a word ("Small", "SME", "Mid-size", "Enterprise") or a headcount ("11-50", "250+"), whose first
 * number decides (under 50 small, under 250 medium, under 1000 large, else enterprise). */
public enum CompanySize {
    SMALL,
    MEDIUM,
    LARGE,
    ENTERPRISE;

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*)");

    /** Null when the value says nothing recognisable. */
    public static CompanySize parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.matches(".*\\b(enterprise|corporate|mnc)\\b.*")) return ENTERPRISE;
        if (v.matches(".*\\b(large|big)\\b.*")) return LARGE;
        if (v.matches(".*\\b(medium|mid|mid[\\s-]?size|midsize|mid[\\s-]?market)\\b.*")) return MEDIUM;
        if (v.matches(".*\\b(small|sme|smb|micro|startup|start[\\s-]up)\\b.*")) return SMALL;
        Matcher m = NUMBER.matcher(v);
        if (!m.find()) return null;
        long people;
        try {
            people = Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (people < 50) return SMALL;
        if (people < 250) return MEDIUM;
        if (people < 1000) return LARGE;
        return ENTERPRISE;
    }

    /** Stored value back to the enum; null stays null. */
    public static CompanySize of(String stored) {
        return stored == null ? null : valueOf(stored);
    }
}
