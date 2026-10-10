package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A brand we know by email (V32): one that signed in, or one ops imported for automatic picks. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lead_brand_contact")
public class BrandContact {

    public enum Source { SIGNUP, OPS_IMPORT }

    @Id
    @Column(name = "id")
    private UUID id;

    /** Lower-cased and trimmed; the identity of a brand. */
    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "contact_name", length = 80)
    private String contactName;

    @Column(name = "company_name", length = 120)
    private String companyName;

    @Column(name = "website_url", length = 255)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "industry", length = 32)
    private ShowcaseIndustry industry;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    /** When they first opened a sign-in link; null for imported or never-confirmed contacts. */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    /** Asked for (unticked by default) picks of new films in their industry (Phase D). */
    @Column(name = "auto_picks_opt_in", nullable = false)
    private boolean autoPicksOptIn;

    @Builder.Default
    @Column(name = "auto_cadence_days", nullable = false)
    private int autoCadenceDays = 7;

    @Column(name = "last_auto_pick_at")
    private OffsetDateTime lastAutoPickAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
