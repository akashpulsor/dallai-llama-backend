package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CreativeDirectionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeDirectionFeedbackRepository extends JpaRepository<CreativeDirectionFeedback, UUID> {

    List<CreativeDirectionFeedback> findByCreativeDirectionIdInOrderByCreatedAtAsc(List<UUID> creativeDirectionIds);

    List<CreativeDirectionFeedback> findByCreativeDirectionIdOrderByCreatedAtAsc(UUID creativeDirectionId);
}
