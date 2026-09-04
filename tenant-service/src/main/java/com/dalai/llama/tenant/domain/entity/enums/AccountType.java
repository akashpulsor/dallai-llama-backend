package com.dalai.llama.tenant.domain.entity.enums;

/** A tenant's account-level authorization role -- gates creator-only UI (e.g. the project pricing
 * panel in creator-ui). {@code BRAND} is not yet a real onboarding path, reserved for when a
 * brand/company console is built; every tenant today is {@code CREATOR}. */
public enum AccountType {
    CREATOR,
    BRAND
}
