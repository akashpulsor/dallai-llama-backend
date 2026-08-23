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
}
