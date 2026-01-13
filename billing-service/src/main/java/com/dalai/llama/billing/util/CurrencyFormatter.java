package com.dalai.llama.billing.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CurrencyFormatter {

    private CurrencyFormatter() {}

    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
