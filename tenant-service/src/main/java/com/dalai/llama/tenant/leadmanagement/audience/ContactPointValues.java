package com.dalai.llama.tenant.leadmanagement.audience;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalising and syntax-checking contact point values, so the same address always becomes the
 * same {@code lead_contact_point} row. */
public final class ContactPointValues {

    public enum Kind { EMAIL, PHONE }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$");

    private ContactPointValues() {
    }

    public static String normaliseEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validEmail(String normalised) {
        return normalised.length() <= 254 && EMAIL.matcher(normalised).matches();
    }

    /** {@code +} and digits only; empty when it can't be a phone number (fewer than 8 or more than 15 digits). */
    public static Optional<String> normalisePhone(String raw) {
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) return Optional.empty();
        return Optional.of(trimmed.startsWith("+") ? "+" + digits : digits);
    }

    public static String domainOf(String email) {
        return email.substring(email.lastIndexOf('@') + 1);
    }
}
