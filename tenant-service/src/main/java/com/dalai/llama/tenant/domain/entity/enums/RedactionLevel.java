package com.dalai.llama.tenant.domain.entity.enums;

public enum RedactionLevel {
    NONE,      // Store everything
    PARTIAL,   // Redact PCI (Credit Cards, SSN)
    AGGRESSIVE // Redact PCI + PII (Names, Phone numbers, Emails)
}
