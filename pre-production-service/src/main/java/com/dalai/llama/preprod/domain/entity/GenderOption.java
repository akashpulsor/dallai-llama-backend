package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Master/reference data for CastProfileQuickCreate's Gender dropdown (see V55 migration for why
 * this exists and why it's deliberately just MALE/FEMALE). Same shape as {@link AspectRatioOption}
 * -- string PK matching the wire value callers actually send. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "gender_option")
public class GenderOption {

    @Id
    @Column(name = "code", length = 16)
    private String code;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
