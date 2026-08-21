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

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "native_name", length = 128)
    private String nativeName;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
