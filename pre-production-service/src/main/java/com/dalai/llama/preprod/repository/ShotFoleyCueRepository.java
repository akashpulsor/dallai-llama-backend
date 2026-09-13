package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotFoleyCue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShotFoleyCueRepository extends JpaRepository<ShotFoleyCue, UUID> {

    List<ShotFoleyCue> findByShotIdOrderByTimestampMsAsc(UUID shotId);

    /** One query for the whole project, so PrepareBundleAssembler groups in memory instead of
     * issuing a lookup per shot -- the fan-out the bundle exists to avoid. */
    List<ShotFoleyCue> findByShotIdInOrderByTimestampMsAsc(List<UUID> shotIds);

    void deleteByShotId(UUID shotId);
}
