package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotImageRepository extends JpaRepository<ShotImage, UUID> {

    Optional<ShotImage> findByShotIdAndKind(UUID shotId, ShotImageKind kind);

    List<ShotImage> findByShotId(UUID shotId);

    /** Images generated for the project's shots: evidence the film was really made here. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(i) FROM ShotImage i WHERE i.shotId IN (SELECT s.id FROM Shot s WHERE s.projectId = :projectId)")
    long countForProject(@org.springframework.data.repository.query.Param("projectId") UUID projectId);

    List<ShotImage> findByShotIdIn(List<UUID> shotIds);
    List<ShotImage> findByTenantIdAndShotIdIn(UUID tenantId, List<UUID> shotIds);
}
