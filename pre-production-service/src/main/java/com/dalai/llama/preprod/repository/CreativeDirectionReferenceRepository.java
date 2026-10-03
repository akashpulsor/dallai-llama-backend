package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CreativeDirectionReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeDirectionReferenceRepository extends JpaRepository<CreativeDirectionReference, UUID> {

    List<CreativeDirectionReference> findByCreativeDirectionId(UUID creativeDirectionId);

    List<CreativeDirectionReference> findByCreativeDirectionIdIn(List<UUID> creativeDirectionIds);
}
