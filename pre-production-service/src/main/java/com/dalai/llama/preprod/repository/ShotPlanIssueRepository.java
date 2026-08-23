package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotPlanIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShotPlanIssueRepository extends JpaRepository<ShotPlanIssue, UUID> {

    List<ShotPlanIssue> findByProjectIdOrderByShotRefAsc(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
