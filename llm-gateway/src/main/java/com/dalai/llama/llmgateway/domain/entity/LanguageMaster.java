package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "language_master")
public class LanguageMaster {

    @Id
    @Column(name = "language_code", length = 16)
    private String languageCode;

    /** Stable platform language identifier (e.g. {@code}). */
    @Column(name = "platform_code", nullable = false, length = 3)
    private String platformCode;

    /** Optional ISO-15924 script subtag. */
    @Column(name = "script_code", length = 4)
    private String scriptCode;

    /** Optional ISO-3166-1 or UN M.49 region subtag. */
    @Column(name = "region_code", length = 3)
    private String regionCode;

    /** Language selected for a partial platform code, e.g. {@code hi}. */
    @Column(name = "default_language", nullable = false)
    private Boolean defaultLanguage;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "native_name", length = 128)
    private String nativeName;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
