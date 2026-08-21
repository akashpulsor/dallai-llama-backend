package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiqueFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CritiqueFindingRepository extends JpaRepository<CritiqueFinding, UUID> {

    List<CritiqueFinding> findBySessionId(UUID sessionId);
}
