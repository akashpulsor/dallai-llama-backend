package com.dalai.llama.tenant.showcase.repository;

import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseRankingRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShowcaseRankingRunRepository extends JpaRepository<ShowcaseRankingRun, UUID> {

    Optional<ShowcaseRankingRun> findFirstByOrderByRunAtDesc();
}
