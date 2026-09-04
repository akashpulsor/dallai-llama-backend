package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotAssetBatchDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShotAssetBatchDeadLetterRepository extends JpaRepository<ShotAssetBatchDeadLetter, UUID> {

    List<ShotAssetBatchDeadLetter> findByProjectIdAndResolvedFalseOrderByCreatedAtAsc(UUID projectId);
}
