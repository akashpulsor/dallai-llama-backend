package com.dalai.llama.product.util;

import java.util.HashMap;
import java.util.Map;

public final class E164Formatter {

    private E164Formatter() {}

    // Country code to country name mapping (common ones)
    private static final Map<String, String> COUNTRY_CODES = new HashMap<>();

    static {
        COUNTRY_CODES.put("1", "US/CA");
        COUNTRY_CODES.put("91", "IN");
        COUNTRY_CODES.put("44", "GB");
        COUNTRY_CODES.put("49", "DE");
        COUNTRY_CODES.put("33", "FR");
        COUNTRY_CODES.put("61", "AU");
        COUNTRY_CODES.put("81", "JP");
        COUNTRY_CODES.put("86", "CN");
        COUNTRY_CODES.put("971", "AE");
        COUNTRY_CODES.put("65", "SG");
    }

    /**
     * Normalize phone number to E.164 format
     * @param number Phone number (may include spaces, dashes, etc.)
     * @return E.164 formatted number (e.g., +919876543210)
     * @throws IllegalArgumentException if number is invalid
     */
    public static String normalize(String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("Phone number cannot be null or empty");
        }

        // Remove all non-digit characters except leading +
        String cleaned = number.replaceAll("[^0-9+]", "");

        // Must start with +
        if (!cleaned.startsWith("+")) {
            throw new IllegalArgumentException("Number must be in E.164 format (starting with +)");
        }

        // Validate length (E.164 max is 15 digits including country code)
        String digits = cleaned.substring(1);
        if (digits.length() < 7 || digits.length() > 15) {
            throw new IllegalArgumentException("Invalid E.164 number length: " + digits.length());
        }

        // Validate all remaining characters are digits
        if (!digits.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid characters in phone number");
        }

        return cleaned;
    }

    /**
     * Format E.164 number for display
     * @param e164 E.164 formatted number
     * @return Human-readable format (e.g., +91 98765 43210)
     */
    public static String display(String e164) {
        if (e164 == null || !e164.startsWith("+")) {
            return e164;
        }

        String digits = e164.substring(1);

        // Format based on country code
        if (digits.startsWith("91") && digits.length() == 12) {
            // India: +91 XXXXX XXXXX
            return String.format("+91 %s %s",
                    digits.substring(2, 7),
                    digits.substring(7));
        } else if (digits.startsWith("1") && digits.length() == 11) {
            // US/Canada: +1 (XXX) XXX-XXXX
            return String.format("+1 (%s) %s-%s",
                    digits.substring(1, 4),
                    digits.substring(4, 7),
                    digits.substring(7));
        } else if (digits.startsWith("44") && digits.length() >= 11) {
            // UK: +44 XXXX XXXXXX
            return String.format("+44 %s %s",
                    digits.substring(2, 6),
                    digits.substring(6));
        }

        // Default: just add space after country code
        return e164;
    }

    /**
     * Extract country code from E.164 number
     * @param e164 E.164 formatted number
     * @return Country code (e.g., "91" for India)
     */
    public static String extractCountryCode(String e164) {
        if (e164 == null || !e164.startsWith("+")) {
            return null;
        }

        String digits = e164.substring(1);

        // Check for 3-digit country codes first
        if (digits.length() >= 3) {
            String code3 = digits.substring(0, 3);
            if (COUNTRY_CODES.containsKey(code3)) {
                return code3;
            }
        }

        // Check for 2-digit country codes
        if (digits.length() >= 2) {
            String code2 = digits.substring(0, 2);
            if (COUNTRY_CODES.containsKey(code2)) {
                return code2;
            }
        }

        // Check for 1-digit country codes
        if (digits.length() >= 1) {
            String code1 = digits.substring(0, 1);
            if (COUNTRY_CODES.containsKey(code1)) {
                return code1;
            }
        }

        // Default to first 2 digits
        return digits.length() >= 2 ? digits.substring(0, 2) : digits;
    }

    /**
     * Check if number is valid E.164 format
     * @param number Phone number to validate
     * @return true if valid E.164 format
     */
    public static boolean isValid(String number) {
        try {
            normalize(number);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Get national number (without country code)
     * @param e164 E.164 formatted number
     * @return National number
     */
    public static String getNationalNumber(String e164) {
        String countryCode = extractCountryCode(e164);
        if (countryCode == null) {
            return e164;
        }
        return e164.substring(1 + countryCode.length());
    }
}