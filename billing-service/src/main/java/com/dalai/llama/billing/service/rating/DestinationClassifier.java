package com.dalai.llama.billing.service.rating;

import com.dalai.llama.billing.domain.entity.enums.DestinationType;
import com.dalai.llama.billing.util.PhoneNumberParser;
import org.springframework.stereotype.Component;

@Component
public class DestinationClassifier {

    /**
     * Classifies a phone number into destination types for billing purposes.
     * Primarily handles Indian phone numbers with E.164 format.
     */
    public DestinationType classify(String number) {
        if (number == null || number.isBlank()) {
            return DestinationType.UNKNOWN;
        }

        String normalized = PhoneNumberParser.normalizeToE164(number);

        // Indian toll-free: 1800/1860
        if (normalized.matches("^\\+91(1800|1860)\\d{7,10}$")) {
            return DestinationType.TOLL_FREE;
        }

        // Indian mobile: 10 digits starting with 6/7/8/9
        if (normalized.matches("^\\+91[6-9]\\d{9}$")) {
            return DestinationType.MOBILE;
        }

        // Indian landline: starts with area code (1-5)
        // Format: +91[1-5]XXXXXXXX (8-11 digits total after +91)
        if (normalized.matches("^\\+91[1-5]\\d{7,10}$")) {
            return DestinationType.LANDLINE;
        }

        // International: not starting with +91
        if (!normalized.startsWith("+91")) {
            return DestinationType.ISD;
        }

        // Default for Indian numbers that don't match other patterns
        return DestinationType.UNKNOWN;
    }

    /**
     * Determines if two numbers are in the same telecom circle (for LOCAL vs STD).
     * This is a simplified implementation - production would use a proper database.
     */
    public boolean isSameCircle(String number1, String number2) {
        // Extract area codes/circles and compare
        // For now, assume same circle if both are in the same state prefix
        String n1 = PhoneNumberParser.normalizeToE164(number1);
        String n2 = PhoneNumberParser.normalizeToE164(number2);

        if (n1.startsWith("+91") && n2.startsWith("+91")) {
            // Compare first digit after +91 for mobile (6-9) or landline (1-5)
            if (n1.length() >= 5 && n2.length() >= 5) {
                return n1.substring(3, 5).equals(n2.substring(3, 5));
            }
        }

        return false;
    }
}
