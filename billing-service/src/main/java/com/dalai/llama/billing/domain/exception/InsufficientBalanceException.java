package com.dalai.llama.billing.domain.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(BigDecimal available, BigDecimal required) {
        super(
                "Insufficient wallet balance. Available: " +
                        available + ", Required: " + required
        );
    }
}
