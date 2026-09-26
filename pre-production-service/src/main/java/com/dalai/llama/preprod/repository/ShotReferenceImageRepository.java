package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotReferenceImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShotReferenceImageRepository extends JpaRepository<ShotReferenceImage, UUID> {

    /** Ordered listing for the shot page + storyboard tile + video-generation prompt. Ordinal
     * is the creator's chosen upload order (V66 index makes this fast). */
    List<ShotReferenceImage> findByShotIdOrderByOrdinalAsc(UUID shotId);

    /** Bulk fetch when the storyboard/PDF exporter walks every shot in a project at once. */
    List<ShotReferenceImage> findByShotIdInOrderByShotIdAscOrdinalAsc(List<UUID> shotIds);
}
