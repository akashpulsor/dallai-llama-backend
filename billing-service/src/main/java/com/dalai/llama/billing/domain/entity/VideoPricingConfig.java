package com.dalai.llama.billing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** The ops-set per-second video rate -- a single row ({@link #SINGLETON_ID}). See V24. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_pricing_config")
public class VideoPricingConfig {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "base_rate_per_second_inr", nullable = false, precision = 15, scale = 4)
    private BigDecimal baseRatePerSecondInr;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
