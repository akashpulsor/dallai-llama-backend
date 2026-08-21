package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiquePlanCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CritiquePlanCharacterRepository extends JpaRepository<CritiquePlanCharacter, UUID> {

    List<CritiquePlanCharacter> findBySnapshotId(UUID snapshotId);
}
