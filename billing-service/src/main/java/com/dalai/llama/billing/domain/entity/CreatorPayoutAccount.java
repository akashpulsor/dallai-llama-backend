package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Where a creator is paid. The RazorpayX fund account holds the real bank details; this keeps
 * only what the creator needs to recognise it (IFSC, last four digits, or the UPI ID). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_payout_account")
public class CreatorPayoutAccount {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16)
    private PayoutMethod method;

    @Column(name = "holder_name", nullable = false, length = 120)
    private String holderName;

    @Column(name = "ifsc", length = 11)
    private String ifsc;

    @Column(name = "account_last4", length = 4)
    private String accountLast4;

    @Column(name = "vpa", length = 100)
    private String vpa;

    @Column(name = "contact_id", nullable = false, length = 64)
    private String contactId;

    @Column(name = "fund_account_id", nullable = false, length = 64)
    private String fundAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
