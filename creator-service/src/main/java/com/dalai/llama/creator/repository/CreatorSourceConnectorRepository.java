package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorSourceConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorSourceConnectorRepository extends JpaRepository<CreatorSourceConnector, UUID> {

    Optional<CreatorSourceConnector> findByCode(String code);
}
