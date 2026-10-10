package com.dalai.llama.tenant.showcase.domain.entity;

import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One ranking run's decisions (V30), kept so any day's landing page can be explained. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "showcase_ranking_run")
public class ShowcaseRankingRun {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "run_at", nullable = false)
    private OffsetDateTime runAt;

    @Column(name = "maturity", nullable = false, precision = 5, scale = 4)
    private BigDecimal maturity;

    @Enumerated(EnumType.STRING)
    @Column(name = "landing_floor", nullable = false, length = 4)
    private CreatorLevel landingFloor;

    @Enumerated(EnumType.STRING)
    @Column(name = "top_floor", nullable = false, length = 4)
    private CreatorLevel topFloor;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_floor", nullable = false, length = 4)
    private CreatorLevel autoFloor;

    @Column(name = "config_version", nullable = false, length = 40)
    private String configVersion;

    @Column(name = "creators_ranked", nullable = false)
    private int creatorsRanked;

    @Column(name = "items_scored", nullable = false)
    private int itemsScored;
}
