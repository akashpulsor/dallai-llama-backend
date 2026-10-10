package com.dalai.llama.billing.domain.entity.enums;

public enum TransactionType {
    RECHARGE,
    USAGE_DEDUCTION,
    ADJUSTMENT_CREDIT,
    ADJUSTMENT_DEBIT,
    REFUND,
    DID_RENTAL,
    SUBSCRIPTION,
    /** A one-off add-on bought from the wallet (e.g. a pack of outreach mails); see AddonService. */
    ADDON
}