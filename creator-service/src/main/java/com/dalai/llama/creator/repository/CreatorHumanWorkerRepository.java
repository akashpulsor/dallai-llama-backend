package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorHumanWorker;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CreatorHumanWorkerRepository extends JpaRepository<CreatorHumanWorker, String> {

    List<CreatorHumanWorker> findByRoleAndOnlineTrueAndActiveTrueOrderByTotalAssignmentCountAscLastAssignedAtAscLastSeenAtAsc(
            String role,
            Pageable pageable
    );

    List<CreatorHumanWorker> findByRoleInOrderByRoleAscOnlineDescLastSeenAtDesc(Collection<String> roles);
}
