package com.dalai.llama.billing.util;

public final class PhoneNumberParser {

    private PhoneNumberParser() {}

    public static String normalizeToE164(String number) {
        if (number == null) return null;

        String normalized = number.replaceAll("[^0-9+]", "");

        if (normalized.startsWith("0")) {
            normalized = "+91" + normalized.substring(1);
        }

        if (!normalized.startsWith("+")) {
            normalized = "+" + normalized;
        }

        return normalized;
    }
}
