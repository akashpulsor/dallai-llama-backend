package com.dalai.llama.tenant.leadmanagement.domain;

/** Lifecycle state for a {@link com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity}.
 * Phase 1 only ever writes {@link #PROVISIONED} -- the other values are reserved for the
 * later suspension/retirement flows so the enum column already carries the full vocabulary and
 * no migration is needed to expand it. */
public enum CreatorEmailIdentityStatus {
    /** Active: address resolves to the catch-all router and forwards to the backend. */
    PROVISIONED,
    /** Temporarily suspended: still exists in DNS but the backend drops inbound mail. */
    DISABLED,
    /** Creator gone: kept only for audit/history; new mail is rejected. */
    RETIRED
}
