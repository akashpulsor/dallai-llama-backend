package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.PaymentMethodType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_methods")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethod {

    @Id
    private UUID id;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    private PaymentMethodType type;

    private String displayName;
    private String maskedReference;

    private String gatewayToken;
    private String gatewayCustomerId;

    private boolean isDefault;
    private boolean active;

    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;
}
