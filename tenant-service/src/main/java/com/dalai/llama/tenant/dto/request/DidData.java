package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DID details for dialplan configuration
 */

@Builder
public record DidData(
        UUID id,
        String number,           // +919876543210
        String displayNumber,    // +91 98765 43210
        String country,          // IN
        String region,           // Maharashtra
        String city,             // Mumbai
        String status,           // PENDING, ACTIVE
        BigDecimal monthlyRental
) {}

