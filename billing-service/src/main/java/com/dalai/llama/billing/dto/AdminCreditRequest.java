package com.dalai.llama.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Request body for manual wallet credit from the ops dashboard. Reference is required so every
 * manual credit lands in the transaction ledger with an operator-provided reason ("comp for
 * failed shot-list-gen incident 2026-09", "top-up for demo") -- the transactions table is the
 * audit trail. Amount must be positive; a debit is a different endpoint (not yet exposed). */
public record AdminCreditRequest(
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,
        @NotNull @Size(min = 3, max = 200, message = "reference must be 3-200 chars") String reference
) {
}
