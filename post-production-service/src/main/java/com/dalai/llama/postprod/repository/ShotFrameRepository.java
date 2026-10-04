package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.domain.entity.ShotFrame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShotFrameRepository extends JpaRepository<ShotFrame, UUID> {

    List<ShotFrame> findByTenantIdAndShotIdAndClipVersionIdAndModeOrderByTimestampMsAsc(
            UUID tenantId, UUID shotId, UUID clipVersionId, FrameExtractionMode mode);

    List<ShotFrame> findByTenantIdAndShotIdOrderByCreatedAtDescTimestampMsAsc(UUID tenantId, UUID shotId);

    @Modifying
    @Query("delete from ShotFrame f where f.shotId = :shotId and f.clipVersionId = :clipVersionId and f.mode = :mode")
    void deleteExtraction(@Param("shotId") UUID shotId, @Param("clipVersionId") UUID clipVersionId,
                          @Param("mode") FrameExtractionMode mode);
}
