package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreatorAssetRepository extends JpaRepository<CreatorAsset, UUID> {
}
