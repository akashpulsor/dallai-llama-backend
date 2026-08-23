package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ContinuityLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContinuityLockRepository extends JpaRepository<ContinuityLock, UUID> {

    List<ContinuityLock> findByContinuityBibleId(UUID continuityBibleId);

    void deleteByContinuityBibleId(UUID continuityBibleId);
}
