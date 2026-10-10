package com.dalai.llama.tenant.showcase.repository;

import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorPublicProfileRepository extends JpaRepository<CreatorPublicProfile, UUID> {

    Optional<CreatorPublicProfile> findByHandle(String handle);

    boolean existsByHandle(String handle);
}
