package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorTrendSignal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CreatorTrendSignalRepository extends JpaRepository<CreatorTrendSignal, UUID> {

    @Query("""
            select signal
            from CreatorTrendSignal signal
            where signal.categoryCode = :category
              and (:platform is null or signal.targetPlatformCode = :platform)
              and (:country is null or signal.countryCode = :country)
            order by signal.rankScore desc, signal.observedAt desc
            """)
    List<CreatorTrendSignal> findRecentSignals(
            @Param("category") String category,
            @Param("platform") String platform,
            @Param("country") String country,
            Pageable pageable
    );
}
